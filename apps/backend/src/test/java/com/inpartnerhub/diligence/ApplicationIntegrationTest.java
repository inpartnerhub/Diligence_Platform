package com.inpartnerhub.diligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
@Import(TestContainersConfiguration.class)
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
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit.audit_log"))
                .hasStackTraceContaining("append-only");
    }

    @Test
    void rowLevelSecurityEnforcesInformationBarrier() {
        var project = UUID.randomUUID();
        var analyst = UUID.randomUUID();
        var target = UUID.randomUUID();
        var client = UUID.randomUUID();
        var outsider = UUID.randomUUID();
        // Seed as the owner (RLS not applied to the login user outside the runtime role). External members need an
        // account in the project's client/target organization (#155).
        var clientOrg = UUID.randomUUID();
        var targetOrg = UUID.randomUUID();
        jdbc.update("insert into adm.organization (id, name, type) values (?, ?, 'CLIENT'), (?, ?, 'TARGET')",
                clientOrg, "C " + clientOrg, targetOrg, "T " + targetOrg);
        accountWithId(client, clientOrg, "CLIENT");
        accountWithId(target, targetOrg, "TARGET");
        jdbc.update("insert into prj.project (id, code, name, client_organization_id, target_organization_id, created_by) "
                + "values (?, ?, 'P', ?, ?, ?)", project, "RLS-" + project, clientOrg, targetOrg, analyst);
        var workstream = UUID.randomUUID();
        jdbc.update("insert into prj.workstream (id, project_id, type) values (?, ?, 'LEGAL')", workstream, project);
        for (var member : new Object[][] { { analyst, "ANALYST" }, { target, "TARGET" }, { client, "CLIENT" } }) {
            jdbc.update("insert into prj.project_member (id, project_id, user_id, project_role) values (?, ?, ?, ?)",
                    UUID.randomUUID(), project, member[0], member[1]);
        }
        jdbc.update("insert into fnd.finding (id, project_id, workstream_id, number, title, category, description, status, "
                + "created_by) values (?, ?, ?, 'F-LEG-001', 'draft', 'c', 'd', 'DRAFT', ?), "
                + "(?, ?, ?, 'F-LEG-002', 'released', 'c', 'd', 'RELEASED', ?)",
                UUID.randomUUID(), project, workstream, analyst, UUID.randomUUID(), project, workstream, analyst);

        assertThat(findingsVisibleTo(analyst, "analyst")).isEqualTo(2);
        assertThat(findingsVisibleTo(client, "client")).isEqualTo(1);   // released only
        assertThat(findingsVisibleTo(target, "target")).isZero();       // never
        assertThat(findingsVisibleTo(outsider, "analyst")).isZero();    // other projects' data is invisible
        assertThat(projectsVisibleTo(outsider)).isZero();
        assertThat(projectsVisibleTo(target)).isEqualTo(1);
    }

    @Test
    void identityTablesFollowTheProjectsAUserBelongsTo() {
        // #154: external users see themselves, the consultant team, people they share a project with, and the
        // organizations of their projects; never other organizations' people.
        var clientOrg = UUID.randomUUID();
        var targetOrg = UUID.randomUUID();
        var otherOrg = UUID.randomUUID();
        for (var org : new Object[][] { { clientOrg, "CLIENT" }, { targetOrg, "TARGET" }, { otherOrg, "CLIENT" } }) {
            jdbc.update("insert into adm.organization (id, name, type) values (?, ?, ?)", org[0], "Org " + org[0],
                    org[1]);
        }
        var analyst = account(null, "ANALYST");
        var client = account(clientOrg, "CLIENT");
        var target = account(targetOrg, "TARGET");
        var stranger = account(otherOrg, "CLIENT");
        var project = UUID.randomUUID();
        jdbc.update("insert into prj.project (id, code, name, client_organization_id, target_organization_id, created_by) "
                + "values (?, ?, 'P', ?, ?, ?)", project, "ADM-" + project, clientOrg, targetOrg, analyst);
        for (var member : new Object[][] { { analyst, "ANALYST" }, { client, "CLIENT" }, { target, "TARGET" } }) {
            jdbc.update("insert into prj.project_member (id, project_id, user_id, project_role) values (?, ?, ?, ?)",
                    UUID.randomUUID(), project, member[0], member[1]);
        }
        var users = "select count(*) from adm.user_account where id in ('%s', '%s', '%s', '%s')"
                .formatted(analyst, client, target, stranger);
        var orgs = "select count(*) from adm.organization where id in ('%s', '%s', '%s')"
                .formatted(clientOrg, targetOrg, otherOrg);

        assertThat(asUser(client, "client", users)).isEqualTo(3);       // not the other organization's client
        assertThat(asUser(stranger, "client", users)).isEqualTo(2);     // itself and the consultant
        assertThat(asUser(analyst, "analyst", users)).isEqualTo(4);     // internal team: whole directory
        assertThat(asUser(client, "client", orgs)).isEqualTo(2);
        assertThat(asUser(stranger, "client", orgs)).isEqualTo(1);      // only its own organization
        assertThat(asUser(stranger, "client", "select count(*) from adm.invitation")).isZero();
        assertThat(asUser(client, "client", "select count(*) from adm.user_group")).isZero();
        assertThat(asUser(client, "client",
                "with u as (update adm.user_account set locale = 'en' where id = '%s' returning 1) select count(*) from u"
                        .formatted(stranger))).isZero();                   // cannot change someone else
        assertThat(asUser(client, "client",
                "with u as (update adm.user_account set locale = 'en' where id = '%s' returning 1) select count(*) from u"
                        .formatted(client))).isEqualTo(1);                 // own profile
        assertThatThrownBy(() -> asUser(client, "client",
                "with u as (update adm.user_account set global_role = 'PARTNER' where id = '%s' returning 1) select count(*) from u"
                        .formatted(client)))
                .rootCause().hasMessageContaining("only the Super Admin"); // never its own role
    }

    @Test
    void externalMembersMustBelongToTheProjectsOrganizations() {
        // #155: enforced by the database, not only by MemberService.
        var clientOrg = UUID.randomUUID();
        var targetOrg = UUID.randomUUID();
        var otherOrg = UUID.randomUUID();
        jdbc.update("insert into adm.organization (id, name, type) values (?, ?, 'CLIENT'), (?, ?, 'TARGET'), "
                + "(?, ?, 'TARGET')", clientOrg, "C " + clientOrg, targetOrg, "T " + targetOrg, otherOrg, "O " + otherOrg);
        var project = UUID.randomUUID();
        jdbc.update("insert into prj.project (id, code, name, client_organization_id, target_organization_id, created_by) "
                + "values (?, ?, 'P', ?, ?, ?)", project, "ORG-" + project, clientOrg, targetOrg, UUID.randomUUID());
        var target = account(targetOrg, "TARGET");
        var foreignTarget = account(otherOrg, "TARGET");
        var insert = "insert into prj.project_member (id, project_id, user_id, project_role) values (?, ?, ?, ?)";

        jdbc.update(insert, UUID.randomUUID(), project, target, "TARGET");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), project, foreignTarget, "TARGET"))
                .rootCause().hasMessageContaining("project's target organization");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), project, target, "CLIENT"))
                .rootCause().hasMessageContaining("project's client organization");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), project, UUID.randomUUID(), "CLIENT"))
                .rootCause().hasMessageContaining("project's client organization"); // no account at all
        jdbc.update(insert, UUID.randomUUID(), project, foreignTarget, "OBSERVER");      // auditors are not bound
    }

    @Test
    void dataRoomRowsStayInTheirParentsProject() {
        // #181: a folder's parent, a document's folder and a version's document belong to the same project, for every
        // database role (here the owner, below an analyst through the folder_create_on_upload policy).
        var projectA = project();
        var projectB = project();
        var analyst = UUID.randomUUID();
        jdbc.update("insert into prj.project_member (id, project_id, user_id, project_role) values (?, ?, ?, 'ANALYST')",
                UUID.randomUUID(), projectA, analyst);
        var folderA = folder(projectA, null);
        var folderB = folder(projectB, null);
        var documentA = UUID.randomUUID();
        var insertDocument = "insert into dr.document (id, project_id, folder_id, name, content_type, size_bytes, sha256, "
                + "created_by) values (?, ?, ?, ?, 'application/pdf', 1, 'x', ?)";
        jdbc.update(insertDocument, documentA, projectA, folderA, "a.pdf", analyst);
        var insertVersion = "insert into dr.document_version (id, document_id, project_id, version_number, object_key, "
                + "size_bytes, sha256, uploaded_by, file_name, content_type) "
                + "values (?, ?, ?, ?, 'k', 1, 'x', ?, 'a.pdf', 'application/pdf')";

        folder(projectA, folderA);                                                     // same project: fine
        jdbc.update(insertVersion, UUID.randomUUID(), documentA, projectA, 1, analyst);
        assertThatThrownBy(() -> folder(projectA, folderB))
                .rootCause().hasMessageContaining("folder_parent_same_project_fk");
        assertThatThrownBy(() -> jdbc.update(insertDocument, UUID.randomUUID(), projectA, folderB, "b.pdf", analyst))
                .rootCause().hasMessageContaining("document_folder_same_project_fk");
        assertThatThrownBy(() -> jdbc.update(insertVersion, UUID.randomUUID(), documentA, projectB, 2, analyst))
                .rootCause().hasMessageContaining("document_version_document_same_project_fk");
        assertThatThrownBy(() -> asUser(analyst, "analyst",
                "with i as (insert into dr.folder (id, project_id, parent_id, name) values ('%s', '%s', '%s', 'X') "
                        .formatted(UUID.randomUUID(), projectA, folderB) + "returning 1) select count(*) from i"))
                .rootCause().hasMessageContaining("folder_parent_same_project_fk");
    }

    private UUID project() {
        var id = UUID.randomUUID();
        jdbc.update("insert into prj.project (id, code, name, client_organization_id, target_organization_id, created_by) "
                + "values (?, ?, 'P', ?, ?, ?)", id, "DR-" + id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        return id;
    }

    private UUID folder(UUID project, UUID parent) {
        var id = UUID.randomUUID();
        jdbc.update("insert into dr.folder (id, project_id, parent_id, name) values (?, ?, ?, ?)",
                id, project, parent, "F " + id);
        return id;
    }

    private UUID account(UUID organization, String role) {
        return accountWithId(UUID.randomUUID(), organization, role);
    }

    private UUID accountWithId(UUID id, UUID organization, String role) {
        jdbc.update("insert into adm.user_account (id, organization_id, email, display_name, global_role, status) "
                + "values (?, ?, ?, 'U', ?, 'ACTIVE')", id, organization, id + "@example.com", role);
        return id;
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
            jdbc.queryForList("select set_config('app.user_id', ?, true), set_config('app.role', ?, true)",
                    user.toString(), role);
            return jdbc.queryForObject(countQuery, Integer.class);
        });
    }
}
