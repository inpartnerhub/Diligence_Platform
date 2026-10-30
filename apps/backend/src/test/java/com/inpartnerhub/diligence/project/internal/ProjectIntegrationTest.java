package com.inpartnerhub.diligence.project.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.inpartnerhub.diligence.TestContainersConfiguration;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

/**
 * Sprint 3–4 against real PostgreSQL row-level security: project bootstrap by its creator (V6), team membership,
 * project isolation and the dashboard information barrier (PRD Bab 3, PRJ-01, PRJ-02, PRJ-04).
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only"
})
@Import(TestContainersConfiguration.class)
class ProjectIntegrationTest {

    @Autowired
    ProjectService projects;

    @Autowired
    MemberService members;

    @Autowired
    DashboardService dashboards;

    @Autowired
    JdbcTemplate jdbc;

    private final UUID clientOrg = UUID.randomUUID();
    private final UUID targetOrg = UUID.randomUUID();
    private final UUID pm = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final UUID outsider = UUID.randomUUID();

    @BeforeEach
    void seedDirectory() {
        // adm.* has no row-level security; seeding runs as the login (owner) role outside the RLS context.
        jdbc.update("insert into adm.organization (id, name, type) values (?, ?, 'CLIENT'), (?, ?, 'TARGET')",
                clientOrg, "Client " + clientOrg, targetOrg, "Target " + targetOrg);
        seedUser(pm, null, Role.PROJECT_MANAGER);
        seedUser(target, targetOrg, Role.TARGET);
        seedUser(outsider, null, Role.ANALYST);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void creatorBootstrapsProjectAndRowLevelSecurityIsolatesIt() {
        signIn(pm, Role.PROJECT_MANAGER);
        var project = projects.create(new ProjectService.CreateCommand("RLS-" + pm.toString().substring(0, 8),
                "Atlas", clientOrg, targetOrg, LocalDate.now(), LocalDate.now().plusMonths(3), null,
                Set.of(WorkstreamType.LEGAL, WorkstreamType.FINANCIAL)));
        var targetMember = members.add(project.getId(),
                new MemberService.AddCommand(target, ProjectRole.TARGET, null, null));

        assertThat(projects.workstreams(project.getId())).hasSize(2);
        assertThat(members.list(project.getId())).extracting(ProjectMember::getRole)
                .containsExactlyInAnyOrder(ProjectRole.PROJECT_MANAGER, ProjectRole.TARGET);

        // Seed one released HIGH finding and request items directly (their modules arrive in later sprints).
        var legal = projects.workstreams(project.getId()).stream()
                .filter(w -> w.getType() == WorkstreamType.LEGAL).findFirst().orElseThrow();
        jdbc.update("insert into fnd.finding (id, project_id, workstream_id, title, category, description, "
                + "risk_level, status, created_by) values (?, ?, ?, 't', 'c', 'd', 'HIGH', 'RELEASED', ?)",
                UUID.randomUUID(), project.getId(), legal.getId(), pm);
        jdbc.update("insert into req.request_item (id, project_id, workstream_id, number, category, description, "
                + "status, due_date) values (?, ?, ?, 'L-1', 'Corporate', 'Deed', 'RECEIVED', null), "
                + "(?, ?, ?, 'L-2', 'Contracts', 'Top 20', 'REQUESTED', ?)",
                UUID.randomUUID(), project.getId(), legal.getId(),
                UUID.randomUUID(), project.getId(), legal.getId(), LocalDate.now().minusDays(1));

        var managerView = dashboards.dashboard(project.getId());
        assertThat(managerView.findingsByRisk()).containsEntry("HIGH", 1L);
        assertThat(managerView.completionPercent()).isEqualTo(50);
        assertThat(managerView.overdue()).isEqualTo(1);

        // The target sees the project and its request progress, but never findings (information barrier).
        signIn(target, Role.TARGET);
        assertThat(projects.list(new ProjectService.ProjectFilter(null, null), PageRequest.of(0, 10)))
                .extracting(Project::getId).containsExactly(project.getId());
        var targetView = dashboards.dashboard(project.getId());
        assertThat(targetView.findingsByRisk().values()).allMatch(count -> count == 0L);
        assertThat(targetView.completionPercent()).isEqualTo(50);
        assertThat(members.list(project.getId())).extracting(ProjectMember::getId)
                .containsExactly(targetMember.getId()); // external users only see their own membership

        // Someone outside the project cannot even tell that it exists.
        signIn(outsider, Role.ANALYST);
        assertThat(projects.list(new ProjectService.ProjectFilter(null, null), PageRequest.of(0, 10))).isEmpty();
        assertThatThrownBy(() -> projects.get(project.getId())).isInstanceOf(ResourceNotFoundException.class);

        // Archiving revokes the target's access.
        signIn(pm, Role.PROJECT_MANAGER);
        projects.archive(project.getId());
        signIn(target, Role.TARGET);
        assertThat(projects.list(new ProjectService.ProjectFilter(null, null), PageRequest.of(0, 10))).isEmpty();
    }

    private void seedUser(UUID id, UUID organizationId, Role role) {
        jdbc.update("insert into adm.user_account (id, organization_id, email, display_name, global_role, status) "
                + "values (?, ?, ?, ?, ?, 'ACTIVE')", id, organizationId, id + "@x.test", role.name(), role.name());
    }

    private static void signIn(UUID userId, Role role) {
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject(userId.toString())
                .claim("preferred_username", userId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority(role.authority())), userId.toString()));
    }
}
