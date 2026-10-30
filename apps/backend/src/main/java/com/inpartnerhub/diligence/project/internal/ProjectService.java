package com.inpartnerhub.diligence.project.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.admin.Directory.OrganizationType;
import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.project.ProjectEvents.ProjectCreated;
import com.inpartnerhub.diligence.project.ProjectEvents.ProjectUpdated;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.project.ProjectStage;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

/** Project lifecycle (PRD PRJ-01): create from scratch or template, update, archive, active workstreams. */
@Service
@Transactional
class ProjectService {

    record CreateCommand(String code, String name, UUID clientOrganizationId, UUID targetOrganizationId,
            LocalDate startDate, LocalDate endDate, UUID templateId, Set<WorkstreamType> workstreams) {
    }

    record ProjectFilter(Project.Status status, String query) {
    }

    private final ProjectRepository projects;
    private final WorkstreamRepository workstreams;
    private final ProjectMemberRepository members;
    private final TemplateService templates;
    private final ProjectAccess access;
    private final Directory directory;
    private final CurrentUserProvider currentUser;
    private final ApplicationEventPublisher events;

    ProjectService(ProjectRepository projects, WorkstreamRepository workstreams, ProjectMemberRepository members,
            TemplateService templates, ProjectAccess access, Directory directory, CurrentUserProvider currentUser,
            ApplicationEventPublisher events) {
        this.projects = projects;
        this.workstreams = workstreams;
        this.members = members;
        this.templates = templates;
        this.access = access;
        this.directory = directory;
        this.currentUser = currentUser;
        this.events = events;
    }

    @Transactional(readOnly = true)
    Page<Project> list(ProjectFilter filter, Pageable pageable) {
        Specification<Project> spec = (root, query, cb) -> cb.conjunction();
        if (filter.status() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), filter.status()));
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            var pattern = "%" + filter.query().strip().toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, query, cb) -> cb.or(cb.like(cb.lower(root.get("code")), pattern),
                    cb.like(cb.lower(root.get("name")), pattern)));
        }
        return projects.findAll(spec, pageable);
    }

    Project create(CreateCommand command) {
        var user = currentUser.requireCurrentUser();
        requireOrganization(command.clientOrganizationId(), OrganizationType.CLIENT);
        requireOrganization(command.targetOrganizationId(), OrganizationType.TARGET);
        Project.validateDates(command.startDate(), command.endDate());

        var selected = EnumSet.noneOf(WorkstreamType.class);
        if (command.workstreams() != null) {
            selected.addAll(command.workstreams());
        }
        if (command.templateId() != null && selected.isEmpty()) {
            selected.addAll(templates.definition(command.templateId()).workstreams());
        }
        if (selected.isEmpty()) {
            throw new InvalidRequestException("Select at least one workstream or a template");
        }

        var project = projects.saveAndFlush(new Project(command.code().strip().toUpperCase(Locale.ROOT),
                command.name().strip(), command.clientOrganizationId(), command.targetOrganizationId(),
                command.startDate(), command.endDate(), command.templateId(), user.id()));
        var creatorRole = user.hasRole(Role.PARTNER) ? ProjectRole.PARTNER : ProjectRole.PROJECT_MANAGER;
        members.save(new ProjectMember(project.getId(), user.id(), creatorRole, null, null));
        selected.forEach(type -> workstreams.save(new Workstream(project.getId(), type)));
        events.publishEvent(new ProjectCreated(project.getId(), project.getCode(), RequestMetadata.capture()));
        return project;
    }

    @Transactional(readOnly = true)
    Project get(UUID id) {
        return access.visible(id);
    }

    Project update(UUID id, String name, LocalDate startDate, LocalDate endDate, ProjectStage stage) {
        var project = access.editable(id);
        project.update(name, startDate, endDate, stage);
        events.publishEvent(new ProjectUpdated(id, "updated", RequestMetadata.capture()));
        return project;
    }

    /** Archives the project and revokes external access (PRD Bab 5, stage 8). */
    Project archive(UUID id) {
        var project = access.manageable(id);
        if (project.isArchived()) {
            return project;
        }
        var now = Instant.now();
        project.archive(now);
        members.findByProjectIdOrderByCreatedAt(id).stream()
                .filter(m -> m.getRole().isExternal())
                .forEach(m -> m.expireAt(now));
        events.publishEvent(new ProjectUpdated(id, "archived", RequestMetadata.capture()));
        return project;
    }

    Project reactivate(UUID id) {
        var project = access.manageable(id);
        if (project.isArchived()) {
            project.reactivate();
            events.publishEvent(new ProjectUpdated(id, "reactivated", RequestMetadata.capture()));
        }
        return project;
    }

    @Transactional(readOnly = true)
    List<Workstream> workstreams(UUID projectId) {
        access.visible(projectId);
        return workstreams.findByProjectIdOrderByType(projectId);
    }

    /** Replaces the active workstreams (PRD PRJ-01). Members scoped to a removed workstream are removed with it. */
    List<Workstream> replaceWorkstreams(UUID projectId, Set<WorkstreamType> types) {
        access.editable(projectId);
        if (types.isEmpty()) {
            throw new InvalidRequestException("A project needs at least one workstream");
        }
        var existing = workstreams.findByProjectIdOrderByType(projectId);
        existing.stream().filter(w -> !types.contains(w.getType())).forEach(workstreams::delete);
        var present = existing.stream().map(Workstream::getType).toList();
        types.stream().filter(t -> !present.contains(t))
                .forEach(t -> workstreams.save(new Workstream(projectId, t)));
        events.publishEvent(new ProjectUpdated(projectId, "workstreams-changed", RequestMetadata.capture()));
        workstreams.flush();
        return workstreams.findByProjectIdOrderByType(projectId);
    }

    private void requireOrganization(UUID id, OrganizationType type) {
        var organization = directory.findOrganization(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", id));
        if (organization.type() != type || !organization.active()) {
            throw new InvalidRequestException(organization.name() + " is not an active " + type.name().toLowerCase(
                    Locale.ROOT) + " organization");
        }
    }
}
