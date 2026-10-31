package com.inpartnerhub.diligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.inpartnerhub.diligence.security.Role;

/**
 * Object-level authorization across projects (OWASP API1, PRD Bab 10.1 "tested against cross-project access"): a
 * resource of project B is never served through project A's URL, even to someone who is a member of both, and is
 * invisible to people outside B. Each probe has a positive control so a broken fixture cannot pass as a denial.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only"
})
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
class CrossProjectAccessIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private final UUID manager = UUID.randomUUID();   // project manager on A and B
    private final UUID outsider = UUID.randomUUID();  // project manager on A only
    private final UUID projectA = UUID.randomUUID();
    private final UUID projectB = UUID.randomUUID();
    private final UUID folderB = UUID.randomUUID();
    private final UUID documentB = UUID.randomUUID();
    private final UUID findingB = UUID.randomUUID();
    private final UUID itemB = UUID.randomUUID();
    private final UUID questionB = UUID.randomUUID();
    private final UUID reportB = UUID.randomUUID();

    @BeforeEach
    void seedTwoProjects() {
        // Seeded as the owner: row-level security applies only to the runtime role the requests use.
        for (var project : List.of(projectA, projectB)) {
            jdbc.update("insert into prj.project (id, code, name, client_organization_id, target_organization_id, "
                    + "created_by) values (?, ?, 'P', ?, ?, ?)", project, "IDOR-" + project, UUID.randomUUID(),
                    UUID.randomUUID(), manager);
            member(project, manager);
        }
        member(projectA, outsider);
        var workstream = UUID.randomUUID();
        jdbc.update("insert into prj.workstream (id, project_id, type) values (?, ?, 'LEGAL')", workstream, projectB);

        jdbc.update("insert into dr.folder (id, project_id, name) values (?, ?, 'Contracts')", folderB, projectB);
        var version = UUID.randomUUID();
        jdbc.update("insert into dr.document (id, project_id, folder_id, name, content_type, size_bytes, sha256, "
                + "scan_status, created_by) values (?, ?, ?, 'spa.pdf', 'application/pdf', 1, 'h', 'CLEAN', ?)",
                documentB, projectB, folderB, manager);
        jdbc.update("insert into dr.document_version (id, document_id, project_id, version_number, object_key, "
                + "file_name, content_type, size_bytes, sha256, uploaded_by, scan_status) "
                + "values (?, ?, ?, 1, 'k', 'spa.pdf', 'application/pdf', 1, 'h', ?, 'CLEAN')",
                version, documentB, projectB, manager);
        jdbc.update("update dr.document set current_version_id = ? where id = ?", version, documentB);

        jdbc.update("insert into fnd.finding (id, project_id, workstream_id, number, title, category, description, "
                + "status, created_by) values (?, ?, ?, 'F-LEG-001', 't', 'c', 'd', 'DRAFT', ?)",
                findingB, projectB, workstream, manager);
        jdbc.update("insert into req.request_item (id, project_id, workstream_id, number, category, description) "
                + "values (?, ?, ?, 'LEG-001', 'c', 'd')", itemB, projectB, workstream);
        jdbc.update("insert into qna.question (id, project_id, workstream_id, number, asked_by, body) "
                + "values (?, ?, ?, 'Q-001', ?, 'b')", questionB, projectB, workstream, manager);
        jdbc.update("insert into rpt.report (id, project_id, version_number, title, created_by) "
                + "values (?, ?, 1, 'Report', ?)", reportB, projectB, manager);
    }

    @Test
    void resourcesOfOneProjectAreNeverServedThroughAnother() throws Exception {
        for (var path : List.of(
                "/documents/" + documentB + "/versions",
                "/documents/" + documentB + "/preview",
                "/documents/" + documentB + "/download",
                "/folders/" + folderB + "/documents",
                "/folders/" + folderB + "/access",
                "/findings/" + findingB,
                "/requests/" + itemB,
                "/questions/" + questionB,
                "/reports/" + reportB + "/download")) {
            denied(get("/api/v1/projects/" + projectA + path), manager);
        }
    }

    @Test
    void membersOfOtherProjectsSeeNothing() throws Exception {
        for (var path : List.of("", "/dashboard", "/members", "/folders", "/documents/" + documentB + "/versions",
                "/findings", "/findings/" + findingB, "/requests", "/requests/" + itemB, "/questions",
                "/questions/" + questionB, "/reports", "/search?q=spa")) {
            denied(get("/api/v1/projects/" + projectB + path), outsider);
        }
    }

    @Test
    void theSameResourcesAreReachableThroughTheirOwnProject() throws Exception {
        for (var path : List.of("/documents/" + documentB + "/versions", "/folders/" + folderB + "/documents",
                "/findings/" + findingB, "/requests/" + itemB, "/questions/" + questionB, "/reports")) {
            mvc.perform(get("/api/v1/projects/" + projectB + path).with(as(manager))).andExpect(status().isOk());
        }
    }

    private void denied(MockHttpServletRequestBuilder request, UUID user) throws Exception {
        var result = mvc.perform(request.with(as(user))).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("%s %s", result.getRequest().getMethod(), result.getRequest().getRequestURI())
                .isIn(403, 404);
    }

    private void member(UUID project, UUID user) {
        jdbc.update("insert into prj.project_member (id, project_id, user_id, project_role) "
                + "values (?, ?, ?, 'PROJECT_MANAGER')", UUID.randomUUID(), project, user);
    }

    private static RequestPostProcessor as(UUID user) {
        return jwt().jwt(j -> j.subject(user.toString()).claim("preferred_username", "u-" + user)
                        .claim("email", user + "@example.com"))
                .authorities(new SimpleGrantedAuthority(Role.PROJECT_MANAGER.authority()));
    }
}
