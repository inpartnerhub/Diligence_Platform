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

/**
 * #177: the application logs in as {@code diligence_app}, not as the schema owner. Its connection cannot change the
 * schema or switch off the audit log's triggers, and a statement outside the row-level-security transaction context
 * sees no project data. SECURITY DEFINER functions put {@code pg_temp} last in their search_path.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only"
})
@Import(TestContainersConfiguration.class)
class RuntimeDatabaseLoginIntegrationTest {

    /** The application's own connection. */
    @Autowired
    JdbcTemplate app;

    JdbcTemplate owner;

    @Autowired
    void ownerDatabase(TestContainersConfiguration.OwnerDatabase database) {
        owner = database.jdbc();
    }

    @Test
    void theApplicationLogsInAsTheRuntimeRoleWithoutPrivilegesOverTheSchema() {
        assertThat(app.queryForObject("select current_user", String.class)).isEqualTo("diligence_app");
        assertThat(app.queryForObject("select rolsuper or rolbypassrls or rolcreaterole or rolcreatedb from pg_roles "
                + "where rolname = current_user", Boolean.class)).isFalse();

        assertThatThrownBy(() -> app.execute("alter table audit.audit_log disable trigger all"))
                .rootCause().hasMessageContaining("must be owner");
        assertThatThrownBy(() -> app.execute("drop table prj.project"))
                .rootCause().hasMessageContaining("must be owner");
        assertThatThrownBy(() -> app.execute("alter table prj.project disable row level security"))
                .rootCause().hasMessageContaining("must be owner");
        assertThatThrownBy(() -> app.execute("create table prj.intruder (id int)"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> app.execute("create table public.intruder (id int)"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> app.update("delete from audit.audit_log"))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void outsideTheRowLevelSecurityContextNoProjectIsVisible() {
        var project = UUID.randomUUID();
        owner.update("insert into prj.project (id, code, name, client_organization_id, target_organization_id, created_by) "
                + "values (?, ?, 'P', ?, ?, ?)", project, "RT-" + project, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());

        assertThat(owner.queryForObject("select count(*) from prj.project where id = ?", Integer.class, project))
                .isEqualTo(1);
        assertThat(app.queryForObject("select count(*) from prj.project where id = ?", Integer.class, project))
                .isZero(); // no transaction, so no app.user_id / app.role: the policies show nothing
    }

    @Test
    void securityDefinerFunctionsSearchTheTemporarySchemaLast() {
        var offenders = owner.queryForList("""
                select p.oid::regprocedure::text
                from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                where p.prosecdef
                  and n.nspname in ('adm', 'ntf', 'prj', 'req', 'dr', 'fnd', 'qna', 'rpt', 'audit', 'public')
                  and not exists (select 1 from unnest(p.proconfig) cfg
                                  where cfg like 'search_path=%' and cfg like '%pg_temp')
                """, String.class);
        assertThat(offenders).as("SECURITY DEFINER functions without pg_temp last in search_path").isEmpty();
        assertThat(owner.queryForObject("select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                + "where p.prosecdef and n.nspname in ('adm', 'prj', 'dr')", Integer.class)).isPositive();
    }
}
