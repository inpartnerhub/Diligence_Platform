package com.inpartnerhub.diligence.qna.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.ingestion.PreviewRenderer;
import com.inpartnerhub.diligence.notification.EmailSender;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.project.internal.ProjectTestApi;
import com.inpartnerhub.diligence.report.internal.ReportTestApi;
import com.inpartnerhub.diligence.security.Role;

/**
 * Sprint 13–14 end to end against real PostgreSQL: approval before the target sees a question, the client's answer
 * held back until the team closes the question (row-level security, V11), project-wide numbering for client questions,
 * and report generation from released findings, issued by a partner and then immutable.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only"
})
@Import({ TestContainersConfiguration.class, ProjectTestApi.class, ReportTestApi.class })
class QnaAndReportIntegrationTest {

    @Autowired
    ProjectTestApi projects;

    @Autowired
    ReportTestApi reports;

    @Autowired
    QuestionService questions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoBean
    EmailSender email;

    @MockitoBean
    DocumentStorage storage;

    @MockitoBean
    PreviewRenderer renderer;

    private final UUID clientOrg = UUID.randomUUID();
    private final UUID targetOrg = UUID.randomUUID();
    private final UUID pm = UUID.randomUUID();
    private final UUID partner = UUID.randomUUID();
    private final UUID client = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("insert into adm.organization (id, name, type) values (?, ?, 'CLIENT'), (?, ?, 'TARGET')",
                clientOrg, "C " + clientOrg, targetOrg, "T " + targetOrg);
        jdbc.update("insert into adm.user_account (id, organization_id, email, display_name, global_role, status) "
                + "values (?, null, ?, 'PM', 'PROJECT_MANAGER', 'ACTIVE'), (?, null, ?, 'Partner', 'PARTNER', 'ACTIVE'), "
                + "(?, ?, ?, 'Client', 'CLIENT', 'ACTIVE'), (?, ?, ?, 'Target', 'TARGET', 'ACTIVE')",
                pm, pm + "@x.test", partner, partner + "@x.test", client, clientOrg, client + "@x.test", target,
                targetOrg, target + "@x.test");
        doAnswer(inv -> {
            ((InputStream) inv.getArgument(1)).readAllBytes();
            return null;
        }).when(storage).put(anyString(), any(), anyLong(), anyString());
        doAnswer(inv -> Files.write((Path) inv.getArgument(3), "%PDF-1.7".getBytes()))
                .when(renderer).renderPdf(any(), anyString(), anyString(), any());
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void approvalBarrierAndReports() {
        signIn(pm, Role.PROJECT_MANAGER);
        var projectId = projects.createWithRequestItems(pm.toString().substring(0, 8), clientOrg, targetOrg,
                List.of(WorkstreamType.LEGAL), List.of(), List.of());
        projects.addMember(projectId, partner, "PARTNER");
        projects.addMember(projectId, client, "CLIENT");
        projects.addMember(projectId, target, "TARGET");

        // The team drafts a question; the target does not see drafts.
        var draft = questions.create(projectId, command("Who owns plot 12?"), false);
        signIn(target, Role.TARGET);
        assertThat(questions.list(projectId, QuestionService.QuestionFilter.ALL)).isEmpty();

        // The client asks; numbering counts questions the client cannot see.
        signIn(client, Role.CLIENT);
        var asked = questions.create(projectId, command("Are there pending tax audits?"), false);
        assertThat(draft.getNumber()).isEqualTo("Q-001");
        assertThat(asked.getNumber()).isEqualTo("Q-002");
        assertThat(asked.getStatus()).isEqualTo(QuestionStatus.PENDING_APPROVAL);
        assertThat(questions.list(projectId, QuestionService.QuestionFilter.ALL)).extracting(Question::getNumber)
                .containsExactly("Q-002");
        await(() -> notifications(pm, "QUESTION_PENDING_APPROVAL") == 1);

        // The PM approves; the target sees and answers it.
        signIn(pm, Role.PROJECT_MANAGER);
        questions.changeStatus(projectId, asked.getId(), QuestionStatus.SENT, null);
        await(() -> notifications(target, "QUESTION_SENT") == 1);
        signIn(target, Role.TARGET);
        assertThat(questions.list(projectId, QuestionService.QuestionFilter.ALL)).extracting(Question::getNumber)
                .containsExactly("Q-002");
        questions.answer(projectId, asked.getId(), "One audit for 2023, closing in Q1");
        await(() -> notifications(pm, "QUESTION_ANSWERED") == 1);

        // The client does not read the answer before the team closes the question.
        signIn(client, Role.CLIENT);
        assertThat(questions.detail(projectId, asked.getId()).answer()).isEmpty();
        assertThat(notifications(client, "QUESTION_ANSWERED")).isZero();
        signIn(pm, Role.PROJECT_MANAGER);
        questions.changeStatus(projectId, asked.getId(), QuestionStatus.CLOSED, null);
        signIn(client, Role.CLIENT);
        assertThat(questions.detail(projectId, asked.getId()).answer()).get()
                .extracting(Answer::getBody).isEqualTo("One audit for 2023, closing in Q1");
        await(() -> notifications(client, "QUESTION_CLOSED") == 1);
        assertThat(rowsVisibleTo(client, "client", "select count(*) from qna.question_status_history")).isZero();

        // Reports: generated from released findings, invisible to the client until a partner issues them.
        var legal = UUID.fromString(jdbc.queryForObject("select id::text from prj.workstream where project_id = ?",
                String.class, projectId));
        jdbc.update("insert into fnd.finding (id, project_id, workstream_id, number, title, category, description, "
                + "impact, likelihood, risk_score, risk_level, status, created_by) values (?, ?, ?, 'F-LEG-001', "
                + "'Unregistered land', 'Assets', 'Two plots', 4, 5, 20, 'CRITICAL', 'RELEASED', ?)",
                UUID.randomUUID(), projectId, legal, pm);
        signIn(pm, Role.PROJECT_MANAGER);
        var reportId = reports.generate(projectId, "Due diligence report");
        signIn(client, Role.CLIENT);
        assertThat(reports.versions(projectId)).isZero();
        signIn(pm, Role.PROJECT_MANAGER);
        assertThatThrownBy(() -> reports.issue(projectId, reportId)).isInstanceOf(AccessDeniedException.class);
        signIn(partner, Role.PARTNER);
        reports.issue(projectId, reportId);
        await(() -> notifications(client, "REPORT_ISSUED") == 1);
        signIn(client, Role.CLIENT);
        assertThat(reports.versions(projectId)).isEqualTo(1);

        // Issued reports are immutable, even bypassing the service.
        assertThat(rowsVisibleTo(pm, "project_manager", "with u as (update rpt.report set title = 'x' where id = '"
                + reportId + "' returning 1) select count(*) from u")).isZero();
        assertThat(rowsVisibleTo(target, "target", "select count(*) from rpt.report")).isZero();
    }

    private static QuestionService.QuestionCommand command(String body) {
        return new QuestionService.QuestionCommand(body, null, null, null);
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
