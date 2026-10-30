package com.inpartnerhub.diligence.project.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.project.ProjectStage;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.shared.web.ApiPaths;
import com.inpartnerhub.diligence.shared.web.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Projects are listed for every authenticated user, filtered by row-level security to their memberships. */
@RestController
@RequestMapping(ApiPaths.V1 + "/projects")
@Tag(name = "Projects")
class ProjectController {

    record ProjectResponse(UUID id, String code, String name, UUID clientOrganizationId, String clientName,
            UUID targetOrganizationId, String targetName, ProjectStage stage, Project.Status status,
            LocalDate startDate, LocalDate endDate, UUID templateId, Instant createdAt, Instant archivedAt,
            Set<ProjectRole> myRoles) {
    }

    record WorkstreamResponse(UUID id, WorkstreamType type, UUID leadUserId) {

        static WorkstreamResponse of(Workstream w) {
            return new WorkstreamResponse(w.getId(), w.getType(), w.getLeadUserId());
        }
    }

    record CreateProjectRequest(
            @NotBlank @Size(max = 32) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]*") String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull UUID clientOrganizationId,
            @NotNull UUID targetOrganizationId,
            LocalDate startDate,
            LocalDate endDate,
            UUID templateId,
            Set<WorkstreamType> workstreams) {
    }

    record UpdateProjectRequest(@Size(max = 200) String name, LocalDate startDate, LocalDate endDate,
            ProjectStage stage) {
    }

    record ReplaceWorkstreamsRequest(@NotEmpty Set<WorkstreamType> workstreams) {
    }

    private final ProjectService projects;
    private final ProjectAccess access;
    private final DashboardService dashboards;
    private final Directory directory;

    ProjectController(ProjectService projects, ProjectAccess access, DashboardService dashboards,
            Directory directory) {
        this.projects = projects;
        this.access = access;
        this.dashboards = dashboards;
        this.directory = directory;
    }

    @GetMapping
    @Operation(summary = "Projects the caller can access — PRJ-01")
    PageResponse<ProjectResponse> list(@RequestParam(required = false) Project.Status status,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "createdAt", direction = org.springframework.data.domain.Sort.Direction.DESC)
            Pageable pageable) {
        return PageResponse.of(projects.list(new ProjectService.ProjectFilter(status, q), pageable), this::response);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('PARTNER', 'PROJECT_MANAGER')")
    @Operation(summary = "Create a project from workstreams or a template; the creator becomes its manager — PRJ-01")
    ProjectResponse create(@Valid @RequestBody CreateProjectRequest request) {
        return response(projects.create(new ProjectService.CreateCommand(request.code(), request.name(),
                request.clientOrganizationId(), request.targetOrganizationId(), request.startDate(),
                request.endDate(), request.templateId(), request.workstreams())));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a project")
    ProjectResponse get(@PathVariable UUID id) {
        return response(projects.get(id));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update name, dates or stage (project managers and partners) — PRJ-01")
    ProjectResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateProjectRequest request) {
        return response(projects.update(id, request.name(), request.startDate(), request.endDate(), request.stage()));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "Archive the project and revoke external access — PRJ-01, PRD Bab 5 stage 8")
    ProjectResponse archive(@PathVariable UUID id) {
        return response(projects.archive(id));
    }

    @PostMapping("/{id}/reactivate")
    @Operation(summary = "Reactivate an archived project (external access must be granted again)")
    ProjectResponse reactivate(@PathVariable UUID id) {
        return response(projects.reactivate(id));
    }

    @GetMapping("/{id}/workstreams")
    @Operation(summary = "Active workstreams of a project")
    List<WorkstreamResponse> workstreams(@PathVariable UUID id) {
        return projects.workstreams(id).stream().map(WorkstreamResponse::of).toList();
    }

    @PutMapping("/{id}/workstreams")
    @Operation(summary = "Replace the active workstreams — PRJ-01")
    List<WorkstreamResponse> replaceWorkstreams(@PathVariable UUID id,
            @Valid @RequestBody ReplaceWorkstreamsRequest request) {
        return projects.replaceWorkstreams(id, request.workstreams()).stream().map(WorkstreamResponse::of).toList();
    }

    @GetMapping("/{id}/dashboard")
    @Operation(summary = "Request-list progress, findings by risk level and deadlines — PRJ-04")
    DashboardService.Dashboard dashboard(@PathVariable UUID id) {
        return dashboards.dashboard(id);
    }

    private ProjectResponse response(Project p) {
        return new ProjectResponse(p.getId(), p.getCode(), p.getName(), p.getClientOrganizationId(),
                organizationName(p.getClientOrganizationId()), p.getTargetOrganizationId(),
                organizationName(p.getTargetOrganizationId()), p.getStage(), p.getStatus(), p.getStartDate(),
                p.getEndDate(), p.getTemplateId(), p.getCreatedAt(), p.getArchivedAt(), access.rolesOf(p));
    }

    private String organizationName(UUID id) {
        return directory.findOrganization(id).map(Directory.OrganizationSummary::name).orElse(null);
    }
}
