package com.inpartnerhub.diligence.project.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.project.WorkstreamType;

/** Test helper exposing project setup to integration tests of other modules (package-private services). */
public class ProjectTestApi {

    private final ProjectService projects;
    private final MemberService members;
    private final TemplateService templates;

    ProjectTestApi(ProjectService projects, MemberService members, TemplateService templates) {
        this.projects = projects;
        this.members = members;
        this.templates = templates;
    }

    /** Creates a template with the given folders and a project from it, as the signed-in user. */
    public UUID createWithFolders(String suffix, UUID clientOrg, UUID targetOrg, List<String> folders) {
        var template = templates.create("Template " + suffix, null,
                new TemplateDefinition(List.of(WorkstreamType.LEGAL), null, null, folders));
        return projects.create(new ProjectService.CreateCommand("DR-" + suffix, "Data room " + suffix, clientOrg,
                targetOrg, LocalDate.now(), null, template.getId(), Set.of())).getId();
    }

    public void addMember(UUID projectId, UUID userId, String role) {
        members.add(projectId, new MemberService.AddCommand(userId, ProjectRole.valueOf(role), null, null));
    }
}
