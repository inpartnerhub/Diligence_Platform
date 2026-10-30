package com.inpartnerhub.diligence.finding.internal;

import static com.inpartnerhub.diligence.finding.internal.FindingTestData.LEGAL;
import static com.inpartnerhub.diligence.finding.internal.FindingTestData.PROJECT;
import static com.inpartnerhub.diligence.finding.internal.FindingTestData.TAX;
import static com.inpartnerhub.diligence.finding.internal.FindingTestData.content;
import static com.inpartnerhub.diligence.finding.internal.FindingTestData.context;
import static com.inpartnerhub.diligence.finding.internal.FindingTestData.finding;
import static com.inpartnerhub.diligence.finding.internal.FindingTestData.scoped;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import com.inpartnerhub.diligence.dataroom.DocumentDirectory;
import com.inpartnerhub.diligence.dataroom.DocumentDirectory.DocumentSummary;
import com.inpartnerhub.diligence.finding.FindingEvents.FindingChanged;
import com.inpartnerhub.diligence.finding.FindingEvents.FindingEscalated;
import com.inpartnerhub.diligence.finding.FindingEvents.FindingStatusChanged;
import com.inpartnerhub.diligence.finding.RiskLevel;
import com.inpartnerhub.diligence.finding.RiskMatrix;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

class FindingServiceTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private final FindingRepository findings = mock(FindingRepository.class);
    private final FindingDocumentRepository links = mock(FindingDocumentRepository.class);
    private final FindingStatusChangeRepository history = mock(FindingStatusChangeRepository.class);
    private final FindingCommentRepository comments = mock(FindingCommentRepository.class);
    private final FindingAccess access = mock(FindingAccess.class);
    private final RiskMatrixService matrix = mock(RiskMatrixService.class);
    private final DocumentDirectory documents = mock(DocumentDirectory.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final FindingService service = new FindingService(findings, links, history, comments, access, matrix,
            documents, currentUser, events, Clock.fixed(Instant.parse("2026-11-10T02:00:00Z"), ZoneOffset.UTC));

    private final List<FindingStatusChange> changes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(currentUser.requireCurrentUser()).thenReturn(new AuthenticatedUser(ME, "me", "me@x.test", "Me",
                Set.of(Role.PROJECT_MANAGER)));
        when(matrix.current()).thenReturn(RiskMatrix.DEFAULT);
        when(findings.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(history.save(any())).thenAnswer(inv -> {
            changes.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(comments.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(access.workstream(any(), eq(LEGAL))).thenReturn(WorkstreamType.LEGAL);
    }

    private void as(ProjectRole role) {
        var context = context(role);
        when(access.project(PROJECT)).thenReturn(context);
        when(access.editableProject(PROJECT)).thenReturn(context);
    }

    private Finding existing(UUID author, FindingStatus status) {
        var finding = finding(author);
        if (status != FindingStatus.DRAFT) {
            finding.moveTo(status, author, Instant.now(), status == FindingStatus.ARCHIVED ? "x" : null);
        }
        when(access.finding(any(), eq(finding.getId()))).thenReturn(finding);
        return finding;
    }

    @Test
    void recordsAndRatesFindingsAndEscalatesHighRisks() {
        as(ProjectRole.ANALYST);
        when(findings.numbersWithPrefix(PROJECT, "F-LEG-")).thenReturn(List.of("F-LEG-001"));

        var created = service.create(PROJECT, content(LEGAL), new FindingService.Rating(4, 4));

        assertThat(created.getNumber()).isEqualTo("F-LEG-002");
        assertThat(created.getCurrency()).isEqualTo("IDR");
        assertThat(created.getMonetaryImpact()).isEqualByComparingTo("1500000000.00");
        assertThat(created.getRiskScore()).isEqualTo(16);
        assertThat(created.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(changes).singleElement().extracting(FindingStatusChange::getToStatus)
                .isEqualTo(FindingStatus.DRAFT);
        verify(events).publishEvent(any(FindingChanged.class));
        var escalation = ArgumentCaptor.forClass(FindingEscalated.class);
        verify(events).publishEvent(escalation.capture());
        assertThat(escalation.getValue().level()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void reRatingOnlyEscalatesWhenTheLevelRises() {
        as(ProjectRole.ANALYST);
        var finding = existing(ME, FindingStatus.DRAFT);
        finding.rate(RiskMatrix.DEFAULT.rate(5, 5));

        service.update(PROJECT, finding.getId(), content(LEGAL), new FindingService.Rating(4, 4));
        verify(events, never()).publishEvent(any(FindingEscalated.class));

        service.update(PROJECT, finding.getId(), content(LEGAL), new FindingService.Rating(null, null));
        assertThat(finding.isRated()).isFalse();
        assertThatThrownBy(() -> service.update(PROJECT, finding.getId(), content(LEGAL),
                new FindingService.Rating(3, null))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void validatesContent() {
        as(ProjectRole.ANALYST);
        var noTitle = new Finding.Content(LEGAL, " ", "c", "d", null, null, null, null);
        assertThatThrownBy(() -> service.create(PROJECT, noTitle, null)).hasMessageContaining("title");
        var negative = new Finding.Content(LEGAL, "t", "c", "d", null, new BigDecimal("-1"), null, null);
        assertThatThrownBy(() -> service.create(PROJECT, negative, null)).isInstanceOf(InvalidRequestException.class);
        var badCurrency = new Finding.Content(LEGAL, "t", "c", "d", null, BigDecimal.TEN, "rupiah", null);
        assertThatThrownBy(() -> service.create(PROJECT, badCurrency, null)).hasMessageContaining("ISO 4217");
        var usd = FindingService.valid(new Finding.Content(LEGAL, "t", "c", "d", null, new BigDecimal("1.005"),
                "usd", null));
        assertThat(usd.currency()).isEqualTo("USD");
        assertThat(usd.monetaryImpact()).isEqualByComparingTo("1.01");
        assertThat(FindingService.valid(new Finding.Content(LEGAL, "t", "c", "d", null, null, "USD", null))
                .currency()).isNull();
        assertThatThrownBy(() -> service.create(PROJECT, content(LEGAL), new FindingService.Rating(6, 1)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void onlyDraftsChangeAndOnlyUnsubmittedDraftsAreDeleted() {
        as(ProjectRole.ANALYST);
        var approved = existing(ME, FindingStatus.APPROVED);
        assertThatThrownBy(() -> service.update(PROJECT, approved.getId(), content(LEGAL), null))
                .isInstanceOf(ResourceConflictException.class);

        var draft = existing(ME, FindingStatus.DRAFT);
        service.delete(PROJECT, draft.getId());
        verify(findings).delete(draft);

        var returned = existing(ME, FindingStatus.IN_REVIEW);
        returned.moveTo(FindingStatus.DRAFT, OTHER, Instant.now(), "fix");
        assertThatThrownBy(() -> service.delete(PROJECT, returned.getId()))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void submittingNeedsARatingAndASourceDocument() {
        as(ProjectRole.ANALYST);
        var finding = existing(ME, FindingStatus.DRAFT);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, finding.getId(), FindingStatus.IN_REVIEW, null))
                .hasMessageContaining("Rate");
        finding.rate(RiskMatrix.DEFAULT.rate(2, 2));
        assertThatThrownBy(() -> service.changeStatus(PROJECT, finding.getId(), FindingStatus.IN_REVIEW, null))
                .hasMessageContaining("source document");

        when(links.existsByFindingId(finding.getId())).thenReturn(true);
        service.changeStatus(PROJECT, finding.getId(), FindingStatus.IN_REVIEW, null);

        assertThat(finding.getStatus()).isEqualTo(FindingStatus.IN_REVIEW);
        assertThat(finding.getSubmittedBy()).isEqualTo(ME);
        assertThat(finding.getSubmittedAt()).isEqualTo(Instant.parse("2026-11-10T02:00:00Z"));
        verify(events).publishEvent(any(FindingStatusChanged.class));
    }

    @Test
    void fourEyesApprovalAndPartnerRelease() {
        as(ProjectRole.ANALYST);
        var own = existing(ME, FindingStatus.IN_REVIEW);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, own.getId(), FindingStatus.APPROVED, null))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("review");

        as(ProjectRole.PROJECT_MANAGER);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, own.getId(), FindingStatus.APPROVED, null))
                .hasMessageContaining("Four eyes");

        var theirs = existing(OTHER, FindingStatus.IN_REVIEW);
        service.changeStatus(PROJECT, theirs.getId(), FindingStatus.APPROVED, null);
        assertThat(theirs.getApprovedBy()).isEqualTo(ME);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, theirs.getId(), FindingStatus.RELEASED, null))
                .hasMessageContaining("partners");

        as(ProjectRole.PARTNER);
        service.changeStatus(PROJECT, theirs.getId(), FindingStatus.RELEASED, null);
        assertThat(theirs.getStatus()).isEqualTo(FindingStatus.RELEASED);
        assertThat(theirs.getReleasedBy()).isEqualTo(ME);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, theirs.getId(), FindingStatus.DRAFT, "x"))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void returningAndArchivingNeedAReasonAndTheRightRole() {
        as(ProjectRole.PROJECT_MANAGER);
        var finding = existing(OTHER, FindingStatus.IN_REVIEW);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, finding.getId(), FindingStatus.DRAFT, " "))
                .hasMessageContaining("reason");
        service.changeStatus(PROJECT, finding.getId(), FindingStatus.DRAFT, "Cite the deed page");
        assertThat(finding.getStatus()).isEqualTo(FindingStatus.DRAFT);
        assertThat(changes).last().extracting(FindingStatusChange::getNote).isEqualTo("Cite the deed page");

        var released = existing(OTHER, FindingStatus.RELEASED);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, released.getId(), FindingStatus.ARCHIVED, "dup"))
                .isInstanceOf(AccessDeniedException.class);
        as(ProjectRole.PARTNER);
        service.changeStatus(PROJECT, released.getId(), FindingStatus.ARCHIVED, "Duplicate of F-LEG-002");
        assertThat(released.getArchiveReason()).isEqualTo("Duplicate of F-LEG-002");

        as(ProjectRole.ANALYST);
        var draft = existing(ME, FindingStatus.DRAFT);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, draft.getId(), FindingStatus.ARCHIVED, "x"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void linksSourceDocumentsWithPages() {
        as(ProjectRole.ANALYST);
        var finding = existing(ME, FindingStatus.DRAFT);
        var documentId = UUID.randomUUID();
        when(documents.find(PROJECT, documentId)).thenReturn(Optional.of(
                new DocumentSummary(documentId, UUID.randomUUID(), "akta.pdf", "application/pdf", 1, "CLEAN")));

        service.linkDocument(PROJECT, finding.getId(), documentId, " p. 12 ", "Clause 4");
        var link = ArgumentCaptor.forClass(FindingDocument.class);
        verify(links).save(link.capture());
        assertThat(link.getValue().getPageReference()).isEqualTo("p. 12");

        var key = new FindingDocument.Key(finding.getId(), documentId);
        when(links.findById(key)).thenReturn(Optional.of(link.getValue()));
        service.linkDocument(PROJECT, finding.getId(), documentId, "p. 13", null);
        assertThat(link.getValue().getPageReference()).isEqualTo("p. 13");

        service.unlinkDocument(PROJECT, finding.getId(), documentId);
        verify(links).delete(link.getValue());

        assertThatThrownBy(() -> service.linkDocument(PROJECT, finding.getId(), UUID.randomUUID(), null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reviewNotesAndDetailHideUnreachableDocuments() {
        as(ProjectRole.PROJECT_MANAGER);
        var finding = existing(OTHER, FindingStatus.IN_REVIEW);
        assertThat(service.comment(PROJECT, finding.getId(), "Check the amount").getBody())
                .isEqualTo("Check the amount");
        assertThatThrownBy(() -> service.comment(PROJECT, finding.getId(), ""))
                .isInstanceOf(InvalidRequestException.class);

        var visible = UUID.randomUUID();
        when(links.findByFindingIdOrderByLinkedAt(finding.getId())).thenReturn(List.of(
                new FindingDocument(finding.getId(), visible, PROJECT, "p. 1", null, OTHER),
                new FindingDocument(finding.getId(), UUID.randomUUID(), PROJECT, null, null, OTHER)));
        when(documents.findAll(eq(PROJECT), anyCollection())).thenReturn(List.of(
                new DocumentSummary(visible, UUID.randomUUID(), "a.pdf", "application/pdf", 1, "CLEAN")));
        var detail = service.detail(PROJECT, finding.getId());
        assertThat(detail.documents()).extracting(d -> d.pageReference()).containsExactly("p. 1");
        assertThat(detail.hiddenDocuments()).isEqualTo(1);

        var archived = existing(OTHER, FindingStatus.ARCHIVED);
        assertThatThrownBy(() -> service.comment(PROJECT, archived.getId(), "late"))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void listsWithinScopeAndFilters() {
        when(access.project(PROJECT)).thenReturn(scoped(ProjectRole.ANALYST, LEGAL));
        var legal = finding(ME);
        legal.rate(RiskMatrix.DEFAULT.rate(5, 4));
        var tax = new Finding(PROJECT, "F-TAX-001", content(TAX), ME);
        when(findings.findByProjectIdOrderByNumber(PROJECT)).thenReturn(List.of(legal, tax));

        assertThat(service.list(PROJECT, FindingService.FindingFilter.ALL)).containsExactly(legal);
        assertThat(service.list(PROJECT, new FindingService.FindingFilter(FindingStatus.DRAFT, LEGAL,
                RiskLevel.CRITICAL, "land"))).containsExactly(legal);
        assertThat(service.list(PROJECT, new FindingService.FindingFilter(null, null, RiskLevel.LOW, null)))
                .isEmpty();

        when(access.project(PROJECT)).thenReturn(context(ProjectRole.CLIENT));
        assertThat(service.list(PROJECT, FindingService.FindingFilter.ALL)).containsExactly(legal, tax);

        when(links.countsByFinding(PROJECT)).thenReturn(List.<Object[]>of(new Object[] { legal.getId(), 2L }));
        assertThat(service.documentCounts(PROJECT)).isEqualTo(Map.of(legal.getId(), 2L));
    }
}
