package com.inpartnerhub.diligence.project.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.security.internal.SecurityConfiguration;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

/** Only partners and project managers create projects; only super admins write templates (PRD Bab 3, PRJ-03). */
@WebMvcTest(controllers = { ProjectController.class, ProjectTemplateController.class },
        properties = { "diligence.security.client-id=diligence-api",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test" })
@Import(SecurityConfiguration.class)
class ProjectEndpointSecurityTest {

    private static final String CREATE_BODY = """
            {"code":"DD-1","name":"Atlas","clientOrganizationId":"%s","targetOrganizationId":"%s",
             "workstreams":["LEGAL"]}""".formatted(UUID.randomUUID(), UUID.randomUUID());

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    ProjectService projects;

    @MockitoBean
    ProjectAccess access;

    @MockitoBean
    DashboardService dashboards;

    @MockitoBean
    TemplateService templates;

    @MockitoBean
    com.inpartnerhub.diligence.admin.Directory directory;

    @Test
    void onlyPartnersAndProjectManagersCreateProjects() throws Exception {
        for (var role : List.of(Role.SUPER_ADMIN, Role.ANALYST, Role.CLIENT, Role.TARGET, Role.OBSERVER)) {
            mvc.perform(post("/api/v1/projects").with(as(role)).contentType(MediaType.APPLICATION_JSON)
                    .content(CREATE_BODY)).andExpect(status().isForbidden());
        }
        var created = ProjectTestData.project(UUID.randomUUID());
        when(projects.create(any())).thenReturn(created);
        when(access.rolesOf(created)).thenReturn(Set.of(ProjectRole.PROJECT_MANAGER));

        mvc.perform(post("/api/v1/projects").with(as(Role.PROJECT_MANAGER)).contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("DD-2026-001"))
                .andExpect(jsonPath("$.myRoles[0]").value("PROJECT_MANAGER"));
    }

    @Test
    void validatesProjectCodeAndMapsBusinessRuleViolations() throws Exception {
        mvc.perform(post("/api/v1/projects").with(as(Role.PARTNER)).contentType(MediaType.APPLICATION_JSON)
                .content(CREATE_BODY.replace("DD-1", "bad code!"))).andExpect(status().isBadRequest());

        when(projects.create(any())).thenThrow(new InvalidRequestException("Select at least one workstream"));
        mvc.perform(post("/api/v1/projects").with(as(Role.PARTNER)).contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:diligence:problem:validation"));
    }

    @Test
    void templatesAreReadableByManagersButWritableBySuperAdminsOnly() throws Exception {
        when(templates.list()).thenReturn(List.of());
        mvc.perform(get("/api/v1/project-templates").with(as(Role.PROJECT_MANAGER))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/project-templates").with(as(Role.TARGET))).andExpect(status().isForbidden());

        var body = """
                {"name":"Full scope","definition":{"workstreams":["LEGAL"]}}""";
        mvc.perform(post("/api/v1/project-templates").with(as(Role.PROJECT_MANAGER))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }

    @Test
    void everyAuthenticatedUserMayListTheirProjects() throws Exception {
        when(projects.list(any(), any())).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
        mvc.perform(get("/api/v1/projects").with(as(Role.TARGET))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized());
    }

    private static RequestPostProcessor as(Role role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority(role.authority()));
    }
}
