package com.inpartnerhub.diligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Boots the full context against real PostgreSQL and RabbitMQ: Flyway migrations validated against Hibernate,
 * append-only audit log, and the row-level-security information barrier (PRD Bab 3).
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only"
})
@Import(ApplicationIntegrationTest.Containers.class)
class ApplicationIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void contextLoadsAndSchemaValidates() {
        assertThat(jdbc.queryForObject("select count(*) from fnd.risk_matrix_config", Integer.class)).isEqualTo(1);
    }

    @Test
    void auditLogIsAppendOnly() {
        jdbc.update("insert into audit.audit_log (id, action, object_type, outcome) values (?, 'test', 'test', 'SUCCESS')",
                UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update("update audit.audit_log set action = 'tampered'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit.audit_log"))
                .hasMessageContaining("append-only");
    }

    @Test
    void rowLevelSecurityEnforcesInformationBarrier() {
        var project = UUID.randomUUID();
        var analyst = UUID.randomUUID();
        var target = UUID.randomUUID();
        var client = UUID.randomUUID();
        var outsider = UUID.randomUUID();
        // Seed as the owner (RLS not applied to the login user outside the runtime role).
        jdbc.update("insert into prj.project (id, code, name, client_organization_id, target_organization_id, created_by) "
                + "values (?, ?, 'P', ?, ?, ?)", project, "RLS-" + project, UUID.randomUUID(), UUID.randomUUID(), analyst);
        var workstream = UUID.randomUUID();
        jdbc.update("insert into prj.workstream (id, project_id, type) values (?, ?, 'LEGAL')", workstream, project);
        for (var member : new Object[][] { { analyst, "ANALYST" }, { target, "TARGET" }, { client, "CLIENT" } }) {
            jdbc.update("insert into prj.project_member (id, project_id, user_id, project_role) values (?, ?, ?, ?)",
                    UUID.randomUUID(), project, member[0], member[1]);
        }
        jdbc.update("insert into fnd.finding (id, project_id, workstream_id, title, category, description, status, created_by) "
                + "values (?, ?, ?, 'draft', 'c', 'd', 'DRAFT', ?), (?, ?, ?, 'released', 'c', 'd', 'RELEASED', ?)",
                UUID.randomUUID(), project, workstream, analyst, UUID.randomUUID(), project, workstream, analyst);

        assertThat(findingsVisibleTo(analyst, "analyst")).isEqualTo(2);
        assertThat(findingsVisibleTo(client, "client")).isEqualTo(1);   // released only
        assertThat(findingsVisibleTo(target, "target")).isZero();       // never
        assertThat(findingsVisibleTo(outsider, "analyst")).isZero();    // other projects' data is invisible
        assertThat(projectsVisibleTo(outsider)).isZero();
        assertThat(projectsVisibleTo(target)).isEqualTo(1);
    }

    private int findingsVisibleTo(UUID user, String role) {
        return asUser(user, role, "select count(*) from fnd.finding");
    }

    private int projectsVisibleTo(UUID user) {
        return asUser(user, "target", "select count(*) from prj.project");
    }

    /** Mirrors RowLevelSecurityTransactionManager: runtime role + per-transaction user context. */
    private int asUser(UUID user, String role, String countQuery) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("SET LOCAL ROLE diligence_app");
            jdbc.queryForObject("select set_config('app.user_id', ?, true), set_config('app.role', ?, true)",
                    String.class, user.toString(), role);
            return jdbc.queryForObject(countQuery, Integer.class);
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbit() {
            return new RabbitMQContainer("rabbitmq:4-management-alpine");
        }
    }
}
