package com.inpartnerhub.diligence.request.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
import com.inpartnerhub.diligence.notification.EmailSender;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.project.internal.ProjectTestApi;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

/**
 * Sprint 9–10 end to end against real PostgreSQL and RabbitMQ: template request list, staged release hidden from the
 * target by row-level security (V9), internal notes behind the information barrier, delivery, notifications written
 * by the asynchronous listeners and daily reminders. The data room is replaced by a test double.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only"
})
@Import({ TestContainersConfiguration.class, ProjectTestApi.class })
class RequestIntegrationTest {

    @Autowired
    ProjectTestApi projects;

    @Autowired
    RequestService requests;

    @Autowired
    RequestReminderService reminders;

    @Autowired
    RequestTransferService transfers;

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
    private final UUID target = UUID.randomUUID();
    private final UUID taxTarget = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc.update("insert into adm.organization (id, name, type) values (?, ?, 'CLIENT'), (?, ?, 'TARGET')",
                clientOrg, "C " + clientOrg, targetOrg, "T " + targetOrg);
        jdbc.update("insert into adm.user_account (id, organization_id, email, display_name, global_role, status) "
                + "values (?, null, ?, 'PM', 'PROJECT_MANAGER', 'ACTIVE'), (?, ?, ?, 'Target', 'TARGET', 'ACTIVE'), "
                + "(?, ?, ?, 'Tax target', 'TARGET', 'ACTIVE')",
                pm, pm + "@x.test", target, targetOrg, target + "@x.test", taxTarget, targetOrg, taxTarget + "@x.test");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void releaseDeliveryReviewAndRemindersRespectTheBarriers() {
        signIn(pm, Role.PROJECT_MANAGER);
        var projectId = projects.createWithRequestItems(pm.toString().substring(0, 8), clientOrg, targetOrg,
                List.of(WorkstreamType.LEGAL, WorkstreamType.TAX),
                List.of(new String[] { "LEGAL", "Corporate", "Deed of establishment" },
                        new String[] { "LEGAL", "Contracts", "Top 20 contracts" },
                        new String[] { "TAX", "Compliance", "Tax returns" }),
                List.of());
        var all = requests.list(projectId, RequestService.ItemFilter.ALL);
        assertThat(all).extracting(RequestItem::getNumber).containsExactly("LEG-001", "LEG-002", "TAX-001");
        var deed = all.get(0);
        var contracts = all.get(1);
        var taxReturns = all.get(2);
        projects.addMember(projectId, target, "TARGET");
        projects.addMember(projectId, taxTarget, "TARGET", taxReturns.getWorkstreamId());

        // Nothing is visible to the target before release (row-level security).
        signIn(target, Role.TARGET);
        assertThat(requests.list(projectId, RequestService.ItemFilter.ALL)).isEmpty();

        signIn(pm, Role.PROJECT_MANAGER);
        requests.update(projectId, contracts.getId(), new RequestService.ItemCommand(contracts.getWorkstreamId(),
                null, "Contracts", "Top 20 contracts", RequestPriority.HIGH, null, 2, null));
        assertThat(requests.release(projectId, 1, null)).hasSize(2);
        awaitNotifications(target, "REQUEST_RELEASED", 1);
        awaitNotifications(taxTarget, "REQUEST_RELEASED", 1);
        requests.comment(projectId, deed.getId(), "Check against the AHU registry", true);
        requests.comment(projectId, deed.getId(), "Please include all amendments", false);

        signIn(target, Role.TARGET);
        assertThat(requests.list(projectId, RequestService.ItemFilter.ALL)).extracting(RequestItem::getNumber)
                .containsExactly("LEG-001", "TAX-001"); // wave 2 is not released yet
        var detail = requests.detail(projectId, deed.getId());
        assertThat(detail.comments()).extracting(RequestComment::getBody)
                .containsExactly("Please include all amendments");
        assertThatThrownBy(() -> requests.detail(projectId, contracts.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(rowsVisibleTo(target, "target", "select count(*) from req.request_item_comment where "
                + "request_item_id = '" + deed.getId() + "'")).isEqualTo(1);

        // The target delivers a document and reports the item as received.
        var document = UUID.randomUUID();
        when(documents.find(eq(projectId), eq(document))).thenReturn(Optional.of(new DocumentSummary(document,
                UUID.randomUUID(), "akta.pdf", "application/pdf", 42, "CLEAN")));
        when(documents.findAll(eq(projectId), any())).thenReturn(List.of(new DocumentSummary(document,
                UUID.randomUUID(), "akta.pdf", "application/pdf", 42, "CLEAN")));
        requests.linkDocument(projectId, deed.getId(), document, "Akta pendirian");
        requests.changeStatus(projectId, deed.getId(), RequestStatus.RECEIVED, null);
        awaitNotifications(pm, "REQUEST_SUBMITTED", 1);

        // The workstream-scoped target only sees its own workstream.
        signIn(taxTarget, Role.TARGET);
        assertThat(requests.list(projectId, RequestService.ItemFilter.ALL)).extracting(RequestItem::getNumber)
                .containsExactly("TAX-001");

        signIn(pm, Role.PROJECT_MANAGER);
        requests.changeStatus(projectId, deed.getId(), RequestStatus.REJECTED, "The copy is not signed");
        awaitNotifications(target, "REQUEST_REVIEWED", 1);
        var history = requests.detail(projectId, deed.getId()).history();
        assertThat(history).extracting(RequestStatusChange::getToStatus).containsExactly(RequestStatus.NOT_REQUESTED,
                RequestStatus.REQUESTED, RequestStatus.RECEIVED, RequestStatus.REJECTED);

        // The export is audited (outbox write inside the export transaction).
        var export = transfers.export(projectId, RequestTransferService.Format.XLSX);
        assertThat(export.content()).isNotEmpty();
        await(() -> jdbc.queryForObject("select count(*) from audit.audit_log where action = 'request.exported' "
                + "and object_id = ?", Integer.class, projectId.toString()) == 1);

        // Daily reminders (system context): the open tax item is overdue.
        jdbc.update("update req.request_item set due_date = ? where id = ?", LocalDate.now().minusDays(1),
                taxReturns.getId());
        SecurityContextHolder.clearContext();
        assertThat(reminders.remind(LocalDate.now())).isGreaterThanOrEqualTo(3);
        assertThat(reminders.remind(LocalDate.now())).isZero(); // at most once per day
        assertThat(notifications(target, "REQUEST_DUE_REMINDER")).isEqualTo(1);
        assertThat(notifications(taxTarget, "REQUEST_DUE_REMINDER")).isEqualTo(1);
        assertThat(notifications(pm, "REQUEST_OVERDUE_DIGEST")).isEqualTo(1);

        // Notifications are private to their recipient.
        assertThat(rowsVisibleTo(target, "target", "select count(*) from ntf.notification where user_id = '"
                + pm + "'")).isZero();
        await(() -> jdbc.queryForObject("select count(*) from audit.audit_log where action = 'request.released' "
                + "and object_id = ?", Integer.class, projectId.toString()) == 1);
    }

    private void awaitNotifications(UUID user, String type, int expected) {
        await(() -> notifications(user, type) >= expected);
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
