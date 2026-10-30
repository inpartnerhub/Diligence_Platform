package com.inpartnerhub.diligence.request.internal;

import static com.inpartnerhub.diligence.request.internal.RequestTestData.LEGAL;
import static com.inpartnerhub.diligence.request.internal.RequestTestData.PROJECT;
import static com.inpartnerhub.diligence.request.internal.RequestTestData.TAX;
import static com.inpartnerhub.diligence.request.internal.RequestTestData.context;
import static com.inpartnerhub.diligence.request.internal.RequestTestData.item;
import static com.inpartnerhub.diligence.request.internal.RequestTestData.scoped;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.MemberSummary;
import com.inpartnerhub.diligence.project.ProjectEvents.ProjectCreated;
import com.inpartnerhub.diligence.project.ProjectEvents.TemplateRequestItem;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.request.RequestEvents.RequestCommentAdded;
import com.inpartnerhub.diligence.request.RequestEvents.RequestDocumentLinked;
import com.inpartnerhub.diligence.request.RequestEvents.RequestItemChanged;
import com.inpartnerhub.diligence.request.RequestEvents.RequestItemsReleased;
import com.inpartnerhub.diligence.request.RequestEvents.RequestStatusChanged;
import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

class RequestServiceTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID TARGET_USER = UUID.randomUUID();

    private final RequestItemRepository items = mock(RequestItemRepository.class);
    private final RequestItemDocumentRepository links = mock(RequestItemDocumentRepository.class);
    private final RequestStatusChangeRepository history = mock(RequestStatusChangeRepository.class);
    private final RequestCommentRepository comments = mock(RequestCommentRepository.class);
    private final RequestAccess access = mock(RequestAccess.class);
    private final ProjectDirectory projects = mock(ProjectDirectory.class);
    private final DocumentDirectory documents = mock(DocumentDirectory.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RequestService service = new RequestService(items, links, history, comments, access, projects,
            documents, currentUser, events, Clock.fixed(Instant.parse("2026-11-02T01:00:00Z"), ZoneOffset.UTC));

    private final List<RequestStatusChange> changes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(currentUser.requireCurrentUser()).thenReturn(new AuthenticatedUser(ME, "me", "me@x.test", "Me",
                Set.of(Role.PROJECT_MANAGER)));
        when(items.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(items.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(history.save(any())).thenAnswer(inv -> {
            changes.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(comments.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(access.workstream(any(), eq(LEGAL))).thenReturn(WorkstreamType.LEGAL);
        when(projects.members(PROJECT)).thenReturn(List.of(new MemberSummary(TARGET_USER, ProjectRole.TARGET, null)));
    }

    private void as(ProjectRole role) {
        var context = context(role);
        when(access.project(PROJECT)).thenReturn(context);
        when(access.writableProject(PROJECT)).thenReturn(context);
        when(access.editableProject(PROJECT)).thenReturn(context);
        when(access.manageableProject(PROJECT)).thenReturn(context);
    }

    private RequestItem existing(RequestStatus status) {
        var item = item("LEG-001", status);
        when(access.item(any(), eq(item.getId()))).thenReturn(item);
        return item;
    }

    @Test
    void createsItemsWithTheNextNumberOfTheWorkstream() {
        as(ProjectRole.PROJECT_MANAGER);
        when(items.numbersWithPrefix(PROJECT, "LEG-")).thenReturn(List.of("LEG-001", "LEG-002"));

        var created = service.create(PROJECT, new RequestService.ItemCommand(LEGAL, null, " Contracts ",
                "Top 20 contracts", null, null, null, TARGET_USER));

        assertThat(created.getNumber()).isEqualTo("LEG-003");
        assertThat(created.getCategory()).isEqualTo("Contracts");
        assertThat(created.getPriority()).isEqualTo(RequestPriority.MEDIUM);
        assertThat(created.getWave()).isEqualTo(1);
        assertThat(created.getAssigneeUserId()).isEqualTo(TARGET_USER);
        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.getFromStatus()).isNull();
            assertThat(c.getToStatus()).isEqualTo(RequestStatus.NOT_REQUESTED);
        });
        verify(events).publishEvent(any(RequestItemChanged.class));
    }

    @Test
    void validatesNumbersDescriptionsWavesAndAssignees() {
        as(ProjectRole.PROJECT_MANAGER);
        when(items.findByProjectIdAndNumber(PROJECT, "LEG-001")).thenReturn(Optional.of(item("LEG-001",
                RequestStatus.NOT_REQUESTED)));

        assertThatThrownBy(() -> service.create(PROJECT, command("LEG-001", "x", 1, null)))
                .isInstanceOf(ResourceConflictException.class);
        assertThatThrownBy(() -> service.create(PROJECT, command("bad number", "x", 1, null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.create(PROJECT, command(null, " ", 1, null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.create(PROJECT, command(null, "x", 21, null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.create(PROJECT, command(null, "x".repeat(2001), 1, null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.create(PROJECT, command(null, "x", 1, ME)))
                .hasMessageContaining("target-side member");
        assertThat(service.create(PROJECT, command("CUSTOM-1", "x", 2, null)).getNumber()).isEqualTo("CUSTOM-1");
    }

    @Test
    void updatesContentAndDeletesOnlyUnreleasedItems() {
        as(ProjectRole.ANALYST);
        var item = existing(RequestStatus.NOT_REQUESTED);
        service.update(PROJECT, item.getId(), new RequestService.ItemCommand(null, null, "Tax", "Updated",
                RequestPriority.HIGH, LocalDate.of(2026, 12, 1), 3, null));
        assertThat(item.getDescription()).isEqualTo("Updated");
        assertThat(item.getPriority()).isEqualTo(RequestPriority.HIGH);
        assertThat(item.getWave()).isEqualTo(3);

        service.delete(PROJECT, item.getId());
        verify(items).delete(item);

        var released = existing(RequestStatus.REQUESTED);
        assertThatThrownBy(() -> service.delete(PROJECT, released.getId()))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void releasesAWaveToTheTarget() {
        as(ProjectRole.PROJECT_MANAGER);
        var legal = item("LEG-001", RequestStatus.NOT_REQUESTED);
        var tax = new RequestItem(PROJECT, TAX, "TAX-001", "c", "d", null, null, 1);
        when(items.findByProjectIdAndStatusAndWave(PROJECT, RequestStatus.NOT_REQUESTED, (short) 1))
                .thenReturn(List.of(legal, tax));

        var released = service.release(PROJECT, 1, LEGAL);

        assertThat(released).containsExactly(legal);
        assertThat(legal.getStatus()).isEqualTo(RequestStatus.REQUESTED);
        assertThat(legal.getRequestedAt()).isEqualTo(Instant.parse("2026-11-02T01:00:00Z"));
        assertThat(tax.getStatus()).isEqualTo(RequestStatus.NOT_REQUESTED);
        var event = ArgumentCaptor.forClass(RequestItemsReleased.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().items()).hasSize(1);
        assertThat(event.getValue().wave()).isEqualTo(1);
    }

    @Test
    void releasingNothingPublishesNothing() {
        as(ProjectRole.PROJECT_MANAGER);
        assertThat(service.release(PROJECT, 2, null)).isEmpty();
        verify(events, never()).publishEvent(any(RequestItemsReleased.class));
    }

    @Test
    void targetReportsDeliveryOnlyWithDocuments() {
        as(ProjectRole.TARGET);
        var item = existing(RequestStatus.REQUESTED);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, item.getId(), RequestStatus.RECEIVED, null))
                .isInstanceOf(InvalidRequestException.class);

        when(links.existsByRequestItemId(item.getId())).thenReturn(true);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, item.getId(), RequestStatus.CLOSED, null))
                .isInstanceOf(ResourceConflictException.class);

        service.changeStatus(PROJECT, item.getId(), RequestStatus.PARTIAL, null);
        assertThat(item.getStatus()).isEqualTo(RequestStatus.PARTIAL);
        var event = ArgumentCaptor.forClass(RequestStatusChanged.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().byTarget()).isTrue();
        assertThat(event.getValue().toStatus()).isEqualTo("PARTIAL");
    }

    @Test
    void rejectionNeedsAReasonThatTheTargetSees() {
        as(ProjectRole.ANALYST);
        var item = existing(RequestStatus.RECEIVED);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, item.getId(), RequestStatus.REJECTED, " "))
                .isInstanceOf(InvalidRequestException.class);

        service.changeStatus(PROJECT, item.getId(), RequestStatus.REJECTED, "Unsigned copy");

        assertThat(item.getStatus()).isEqualTo(RequestStatus.REJECTED);
        var comment = ArgumentCaptor.forClass(RequestComment.class);
        verify(comments).save(comment.capture());
        assertThat(comment.getValue().isInternal()).isFalse();
        assertThat(changes).last().extracting(RequestStatusChange::getNote).isEqualTo("Unsigned copy");
        var event = ArgumentCaptor.forClass(RequestStatusChanged.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().reason()).isEqualTo("Unsigned copy");
    }

    @Test
    void internalTransitionsAreChecked() {
        as(ProjectRole.ANALYST);
        var item = existing(RequestStatus.NOT_REQUESTED);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, item.getId(), RequestStatus.RECEIVED, null))
                .isInstanceOf(ResourceConflictException.class);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, item.getId(), RequestStatus.REQUESTED, null))
                .isInstanceOf(AccessDeniedException.class);

        as(ProjectRole.PROJECT_MANAGER);
        service.changeStatus(PROJECT, item.getId(), RequestStatus.REQUESTED, null);
        verify(events).publishEvent(any(RequestItemsReleased.class));

        when(links.existsByRequestItemId(item.getId())).thenReturn(true);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, item.getId(), RequestStatus.NOT_REQUESTED, null))
                .isInstanceOf(ResourceConflictException.class);

        as(ProjectRole.OBSERVER);
        assertThatThrownBy(() -> service.changeStatus(PROJECT, item.getId(), RequestStatus.CLOSED, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void linksVisibleDocumentsAndOnlyOwnLinksCanBeRemovedByTheTarget() {
        as(ProjectRole.TARGET);
        var item = existing(RequestStatus.REQUESTED);
        var documentId = UUID.randomUUID();
        when(documents.find(PROJECT, documentId)).thenReturn(Optional.of(
                new DocumentSummary(documentId, UUID.randomUUID(), "akta.pdf", "application/pdf", 10, "CLEAN")));

        service.linkDocument(PROJECT, item.getId(), documentId, "  Akta 2020 ");
        var link = ArgumentCaptor.forClass(RequestItemDocument.class);
        verify(links).save(link.capture());
        assertThat(link.getValue().getComment()).isEqualTo("Akta 2020");
        assertThat(link.getValue().getLinkedBy()).isEqualTo(ME);
        verify(events).publishEvent(any(RequestDocumentLinked.class));

        assertThatThrownBy(() -> service.linkDocument(PROJECT, item.getId(), UUID.randomUUID(), null))
                .isInstanceOf(ResourceNotFoundException.class);

        var foreign = new RequestItemDocument(item.getId(), documentId, PROJECT, null, UUID.randomUUID());
        when(links.findById(new RequestItemDocument.Key(item.getId(), documentId))).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.unlinkDocument(PROJECT, item.getId(), documentId))
                .isInstanceOf(AccessDeniedException.class);

        var own = new RequestItemDocument(item.getId(), documentId, PROJECT, null, ME);
        when(links.findById(new RequestItemDocument.Key(item.getId(), documentId))).thenReturn(Optional.of(own));
        service.unlinkDocument(PROJECT, item.getId(), documentId);
        verify(links).delete(own);

        var closed = existing(RequestStatus.CLOSED);
        assertThatThrownBy(() -> service.linkDocument(PROJECT, closed.getId(), documentId, null))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void linkingTheSameDocumentTwiceIsANoOp() {
        as(ProjectRole.ANALYST);
        var item = existing(RequestStatus.PARTIAL);
        var documentId = UUID.randomUUID();
        when(documents.find(PROJECT, documentId)).thenReturn(Optional.of(
                new DocumentSummary(documentId, UUID.randomUUID(), "a.pdf", "application/pdf", 1, "CLEAN")));
        when(links.existsById(new RequestItemDocument.Key(item.getId(), documentId))).thenReturn(true);
        service.linkDocument(PROJECT, item.getId(), documentId, null);
        verify(links, never()).save(any());
    }

    @Test
    void commentsRespectTheInformationBarrier() {
        as(ProjectRole.TARGET);
        var item = existing(RequestStatus.REQUESTED);
        assertThatThrownBy(() -> service.comment(PROJECT, item.getId(), "secret", true))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.comment(PROJECT, item.getId(), " ", false))
                .isInstanceOf(InvalidRequestException.class);
        var comment = service.comment(PROJECT, item.getId(), "Will follow next week", false);
        assertThat(comment.isInternal()).isFalse();
        var event = ArgumentCaptor.forClass(RequestCommentAdded.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().byTarget()).isTrue();

        as(ProjectRole.OBSERVER);
        assertThatThrownBy(() -> service.comment(PROJECT, item.getId(), "x", false))
                .isInstanceOf(AccessDeniedException.class);

        as(ProjectRole.ANALYST);
        assertThat(service.comment(PROJECT, item.getId(), "Check the signatures", true).isInternal()).isTrue();
    }

    @Test
    void detailShowsOnlyDocumentsTheCallerMaySee() {
        as(ProjectRole.TARGET);
        var item = existing(RequestStatus.PARTIAL);
        var visible = UUID.randomUUID();
        var hidden = UUID.randomUUID();
        when(links.findByRequestItemIdOrderByLinkedAt(item.getId())).thenReturn(List.of(
                new RequestItemDocument(item.getId(), visible, PROJECT, null, ME),
                new RequestItemDocument(item.getId(), hidden, PROJECT, null, ME)));
        when(documents.findAll(eq(PROJECT), anyCollection())).thenReturn(List.of(
                new DocumentSummary(visible, UUID.randomUUID(), "a.pdf", "application/pdf", 1, "CLEAN")));

        var detail = service.detail(PROJECT, item.getId());

        assertThat(detail.documents()).extracting(d -> d.document().id()).containsExactly(visible);
        assertThat(detail.hiddenDocuments()).isEqualTo(1);
    }

    @Test
    void listFiltersByScopeAndCriteria() {
        when(access.project(PROJECT)).thenReturn(scoped(ProjectRole.ANALYST, LEGAL));
        var legal = item("LEG-001", RequestStatus.REQUESTED);
        var tax = new RequestItem(PROJECT, TAX, "TAX-001", "c", "Tax returns", null, null, 2);
        when(items.findByProjectIdOrderByNumber(PROJECT)).thenReturn(List.of(legal, tax));

        assertThat(service.list(PROJECT, RequestService.ItemFilter.ALL)).containsExactly(legal);
        assertThat(service.list(PROJECT, new RequestService.ItemFilter(RequestStatus.REQUESTED, LEGAL, 1, "DEED")))
                .containsExactly(legal);
        assertThat(service.list(PROJECT, new RequestService.ItemFilter(null, null, null, "nothing"))).isEmpty();

        when(links.countsByItem(PROJECT)).thenReturn(List.<Object[]>of(new Object[] { legal.getId(), 3L }));
        assertThat(service.documentCounts(PROJECT)).isEqualTo(Map.of(legal.getId(), 3L));
    }

    @Test
    void createsTheTemplateRequestListForNewProjects() {
        var saved = new ArrayList<RequestItem>();
        when(items.save(any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        service.onProjectCreated(new ProjectCreated(PROJECT, "DD-1", List.of(), List.of(
                new TemplateRequestItem(LEGAL, WorkstreamType.LEGAL, "Corporate", "Deed"),
                new TemplateRequestItem(LEGAL, WorkstreamType.LEGAL, null, "Shareholder register"),
                new TemplateRequestItem(TAX, WorkstreamType.TAX, "Compliance", "Tax returns"),
                new TemplateRequestItem(TAX, WorkstreamType.TAX, "Empty", " ")), RequestMetadata.SYSTEM));

        assertThat(saved).extracting(RequestItem::getNumber).containsExactly("LEG-001", "LEG-002", "TAX-001");
        assertThat(saved.get(1).getCategory()).isEqualTo("General");
        assertThat(saved).allMatch(i -> i.getStatus() == RequestStatus.NOT_REQUESTED);
        assertThat(changes).hasSize(3).allMatch(c -> "template".equals(c.getNote()));
    }

    private static RequestService.ItemCommand command(String number, String description, int wave, UUID assignee) {
        return new RequestService.ItemCommand(LEGAL, number, null, description, null, null, wave, assignee);
    }
}
