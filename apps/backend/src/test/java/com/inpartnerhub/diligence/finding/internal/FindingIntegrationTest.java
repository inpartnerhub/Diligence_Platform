package com.inpartnerhub.diligence.finding.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.inpartnerhub.diligence.TestContainersConfiguration;
import com.inpartnerhub.diligence.dataroom.DocumentDirectory;
import com.inpartnerhub.diligence.dataroom.DocumentDirectory.DocumentSummary;
import com.inpartnerhub.diligence.finding.RiskLevel;
import com.inpartnerhub.diligence.notification.EmailSender;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.project.internal.ProjectTestApi;
import com.inpartnerhub.diligence.security.Role;

/**
 * Sprint 11–12 end to end against real PostgreSQL: the finding workflow with four-eyes approval and release by a
 * partner, the information barrier (target: nothing; client: released findings only, never review notes or history),
 * immutability of released findings enforced by row-level security (V10) and re-rating on threshold changes.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only"
})
@Import({ TestContainersConfiguration.class, ProjectTestApi.class })
class FindingIntegrationTest {

    @Autowired
    ProjectTestApi projects;

    @Autowired
    FindingService findings;

    @Autowired
    RiskMatrixService matrix;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoBean
    DocumentDirectory documents;

    @MockitoBean
    EmailSender email;

    private final UUID clientOrg = UUID.randomUUID();
    private final UUID targetOrg = UUID.randomUUID();
    private final UUID pm = UUID.randomUUID();
    private final UUID partner = UUID.randomUUID();
    private final UUID analyst = UUID.randomUUID();
    private final UUID client = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc.update("insert into adm.organization (id, name, type) values (?, ?, 'CLIENT'), (?, ?, 'TARGET')",
                clientOrg, "C " + clientOrg, targetOrg, "T " + targetOrg);
        jdbc.update("insert into adm.user_account (id, organization_id, email, display_name, global_role, status) "
                + "values (?, null, ?, 'PM', 'PROJECT_MANAGER', 'ACTIVE'), (?, null, ?, 'Partner', 'PARTNER', 'ACTIVE'), "
                + "(?, null, ?, 'Analyst', 'ANALYST', 'ACTIVE'), (?, ?, ?, 'Client', 'CLIENT', 'ACTIVE'), "
                + "(?, ?, ?, 'Target', 'TARGET', 'ACTIVE')",
                pm, pm + "@x.test", partner, partner + "@x.test", analyst, analyst + "@x.test", client, clientOrg,
                client + "@x.test", target, targetOrg, target + "@x.test");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
        // Leave the shared single-row configuration as other tests expect it.
        jdbc.update("update fnd.risk_matrix_config set medium_from = 5, high_from = 10, critical_from = 17");
    }

    @Test
    void workflowBarrierAndImmutability() {
        signIn(pm, Role.PROJECT_MANAGER);
        var projectId = projects.createWithRequestItems(pm.toString().substring(0, 8), clientOrg, targetOrg,
                List.of(WorkstreamType.LEGAL), List.of(), List.of());
        projects.addMember(projectId, partner, "PARTNER");
        projects.addMember(projectId, analyst, "ANALYST");
        projects.addMember(projectId, client, "CLIENT");
        projects.addMember(projectId, target, "TARGET");
        var legal = UUID.fromString(jdbc.queryForObject(
                "select id::text from prj.workstream where project_id = ?", String.class, projectId));
        var document = UUID.randomUUID();
        when(documents.find(eq(projectId), eq(document))).thenReturn(Optional.of(new DocumentSummary(document,
                UUID.randomUUID(), "akta.pdf", "application/pdf", 10, "CLEAN")));
        when(documents.findAll(eq(projectId), any())).thenReturn(List.of());

        // The analyst records, rates (16 = HIGH → escalated to the PM) and cites a source, then submits.
        signIn(analyst, Role.ANALYST);
        var content = new Finding.Content(legal, "Unregistered land", "Assets", "Two plots unregistered.", null,
                new BigDecimal("2500000000"), "IDR", "Condition precedent");
        var finding = findings.create(projectId, content, new FindingService.Rating(4, 4));
        assertThat(finding.getNumber()).isEqualTo("F-LEG-001");
        findings.linkDocument(projectId, finding.getId(), document, "p. 12", null);
        findings.changeStatus(projectId, finding.getId(), FindingStatus.IN_REVIEW, null);
        await(() -> notifications(pm, "FINDING_ESCALATED") == 1 && notifications(pm, "FINDING_SUBMITTED") == 1);

        // Information barrier before release.
        signIn(target, Role.TARGET);
        assertThatThrownBy(() -> findings.list(projectId, FindingService.FindingFilter.ALL))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(rowsVisibleTo(target, "target", "select count(*) from fnd.finding")).isZero();
        signIn(client, Role.CLIENT);
        assertThat(findings.list(projectId, FindingService.FindingFilter.ALL)).isEmpty();

        // Review: a note, approval by the PM (not the author), release by the partner.
        signIn(pm, Role.PROJECT_MANAGER);
        findings.comment(projectId, finding.getId(), "Check the certificate numbers");
        findings.changeStatus(projectId, finding.getId(), FindingStatus.APPROVED, null);
        assertThatThrownBy(() -> findings.changeStatus(projectId, finding.getId(), FindingStatus.RELEASED, null))
                .isInstanceOf(AccessDeniedException.class);
        signIn(partner, Role.PARTNER);
        findings.changeStatus(projectId, finding.getId(), FindingStatus.RELEASED, null);
        await(() -> notifications(client, "FINDING_RELEASED") == 1);

        signIn(client, Role.CLIENT);
        var visible = findings.list(projectId, FindingService.FindingFilter.ALL);
        assertThat(visible).extracting(Finding::getNumber).containsExactly("F-LEG-001");
        var detail = findings.detail(projectId, finding.getId());
        assertThat(detail.comments()).isEmpty(); // review notes stay internal
        assertThat(detail.history()).isEmpty();
        assertThat(detail.documents()).isEmpty();

        // Released findings are immutable for the team, even bypassing the service: the update matches no row.
        assertThat(rowsVisibleTo(analyst, "analyst", "with u as (update fnd.finding set title = 'changed' where id = '"
                + finding.getId() + "' returning 1) select count(*) from u")).isZero();

        // A second, open finding is re-rated when the thresholds change; the released one keeps its level.
        signIn(analyst, Role.ANALYST);
        var open = findings.create(projectId, content, new FindingService.Rating(3, 3)); // 9 = MEDIUM
        signIn(admin, Role.SUPER_ADMIN);
        assertThat(matrix.update(4, 8, 15)).isEqualTo(1);
        assertThat(level(open.getId())).isEqualTo("HIGH");
        assertThat(level(finding.getId())).isEqualTo(RiskLevel.HIGH.name());
        await(() -> jdbc.queryForObject("select count(*) from audit.audit_log where action = 'risk-matrix.updated'",
                Integer.class) >= 1);
    }

    private String level(UUID findingId) {
        return jdbc.queryForObject("select risk_level from fnd.finding where id = ?", String.class, findingId);
    }

    private int notifications(UUID user, String type) {
        return jdbc.queryForObject("select count(*) from ntf.notification where user_id = ? and notification_type = ?",
                Integer.class, user, type);
    }

    private int rowsVisibleTo(UUID user, String role, String countQuery) {
        return new TransactionTemplate(transactionManager).execute(s -> {
            jdbc.execute("SET LOCAL ROLE diligence_app");
            jdbc.queryForList("select set_config('app.user_id', ?, true), set_config('app.role', ?, true)",
                    user.toString(), role);
            return jdbc.queryForObject(countQuery, Integer.class);
        });
    }

    private static void await(Supplier<Boolean> condition) {
        var deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (!condition.get()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within 20 s");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        }
    }

    private static void signIn(UUID userId, Role role) {
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject(userId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority(role.authority())), userId.toString()));
    }
}
