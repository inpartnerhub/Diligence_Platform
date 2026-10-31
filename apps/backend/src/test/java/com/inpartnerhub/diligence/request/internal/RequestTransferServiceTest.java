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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.ingestion.PreviewRenderer;
import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectSummary;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.request.RequestEvents.RequestListTransferred;
import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.UpstreamServiceException;

class RequestTransferServiceTest {

    private final RequestItemRepository items = mock(RequestItemRepository.class);
    private final RequestStatusChangeRepository history = mock(RequestStatusChangeRepository.class);
    private final RequestService requests = mock(RequestService.class);
    private final RequestAccess access = mock(RequestAccess.class);
    private final ProjectDirectory projects = mock(ProjectDirectory.class);
    private final Directory directory = mock(Directory.class);
    private final PreviewRenderer renderer = mock(PreviewRenderer.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RequestTransferService service = new RequestTransferService(items, history, requests, access,
            projects, directory, renderer, currentUser, events, ZoneId.of("Asia/Jakarta"));

    private final List<RequestItem> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        var context = context(ProjectRole.PROJECT_MANAGER);
        when(access.project(PROJECT)).thenReturn(context);
        when(access.editableProject(PROJECT)).thenReturn(context);
        when(access.workstreams(PROJECT)).thenReturn(Map.of(LEGAL, WorkstreamType.LEGAL, TAX, WorkstreamType.TAX));
        when(projects.summary(PROJECT)).thenReturn(Optional.of(new ProjectSummary(PROJECT, "DD-1", "Alpha", null, false)));
        when(currentUser.requireCurrentUser()).thenReturn(new AuthenticatedUser(UUID.randomUUID(), "pm", "pm@x.test",
                "PM", Set.of(Role.PROJECT_MANAGER)));
        when(items.save(any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    @Test
    void exportedWorkbooksCanBeImportedAgain() {
        var deed = item("LEG-001", RequestStatus.REQUESTED);
        var assignee = UUID.randomUUID();
        deed.assign(assignee);
        when(requests.list(PROJECT, RequestService.ItemFilter.ALL)).thenReturn(List.of(deed));
        when(requests.documentCounts(PROJECT)).thenReturn(Map.of(deed.getId(), 2L));
        when(directory.findUsers(List.of(assignee))).thenReturn(List.of(new Directory.UserSummary(assignee,
                "t@x.test", "Tari", null, Role.TARGET, true)));

        var file = service.export(PROJECT, RequestTransferService.Format.XLSX);

        assertThat(file.fileName()).startsWith("request-list-dd-1-").endsWith(".xlsx");
        assertThat(file.contentType()).isEqualTo(RequestSpreadsheet.CONTENT_TYPE);
        verify(events).publishEvent(any(RequestListTransferred.class));
        var rows = RequestSpreadsheet.read(new ByteArrayInputStream(file.content()));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get(RequestSpreadsheet.Column.NUMBER)).isEqualTo("LEG-001");
            assertThat(row.get(RequestSpreadsheet.Column.WORKSTREAM)).isEqualTo("LEGAL");
            assertThat(row.dueDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        });
    }

    @Test
    void itemsWithoutAssigneeExport() {
        when(requests.list(PROJECT, RequestService.ItemFilter.ALL)).thenReturn(List.of(item("LEG-001",
                RequestStatus.NOT_REQUESTED)));
        when(requests.documentCounts(PROJECT)).thenReturn(Map.of());

        var file = service.export(PROJECT, RequestTransferService.Format.XLSX);

        assertThat(RequestSpreadsheet.read(new ByteArrayInputStream(file.content()))).hasSize(1);
    }

    @Test
    void pdfExportsAreConvertedByGotenberg() throws IOException {
        when(requests.list(PROJECT, RequestService.ItemFilter.ALL)).thenReturn(List.of());
        doAnswer(inv -> Files.write((Path) inv.getArgument(3), "%PDF-1.7".getBytes()))
                .when(renderer).renderPdf(any(), anyString(), eq(RequestSpreadsheet.CONTENT_TYPE), any());

        var file = service.export(PROJECT, RequestTransferService.Format.PDF);
        assertThat(file.contentType()).isEqualTo("application/pdf");
        assertThat(new String(file.content())).isEqualTo("%PDF-1.7");

        doThrow(new IllegalStateException("down")).when(renderer).renderPdf(any(), anyString(), anyString(), any());
        assertThatThrownBy(() -> service.export(PROJECT, RequestTransferService.Format.PDF))
                .isInstanceOf(UpstreamServiceException.class);
    }

    @Test
    void templateListsTheProjectsWorkstreams() throws IOException {
        var file = service.template(PROJECT);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(1).getStringCellValue()).isEqualTo("Workstream");
            assertThat(workbook.getSheetAt(0).getDataValidations()).hasSize(2);
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(0).getStringCellValue()).contains("TAX, LEGAL");
        }
    }

    @Test
    void dryRunReportsEveryRowAndStoresNothing() {
        var existing = item("LEG-001", RequestStatus.REQUESTED);
        when(items.findByProjectIdOrderByNumber(PROJECT)).thenReturn(List.of(existing));
        var file = workbook(List.of("Nomor", "Bidang", "Kategori", "Deskripsi", "Prioritas", "Tenggat", "Gelombang"),
                List.of("", "LEG", "Contracts", "Top 20 contracts", "tinggi", "2026-12-01", "2"),
                List.of("LEG-001", "LEGAL", "Corporate", "Deed (updated)", "", "", ""),
                List.of("", "HR", "", "Payroll", "", "", ""),
                List.of("", "", "", "", "", "", ""),
                List.of("X 1", "TAX", "", "", "urgent", "31/02/2026", "99"));

        var result = service.importList(PROJECT, file, true);

        assertThat(result.committed()).isFalse();
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.invalid()).isEqualTo(2);
        assertThat(result.rows()).hasSize(4);
        assertThat(result.rows().get(0).number()).isEqualTo("LEG-002");
        assertThat(result.rows().get(1).action()).isEqualTo(RequestTransferService.Action.UPDATE);
        assertThat(result.rows().get(2).errors()).containsExactly("Workstream HR is not part of this project");
        assertThat(result.rows().get(3).row()).isEqualTo(6);
        assertThat(result.rows().get(3).errors()).hasSize(5);
        verify(items, never()).save(any());
    }

    @Test
    void importStoresAllRowsWhenEveryRowIsValid() {
        var existing = item("LEG-001", RequestStatus.REQUESTED);
        existing.assign(UUID.randomUUID());
        when(items.findByProjectIdOrderByNumber(PROJECT)).thenReturn(List.of(existing));
        var file = workbook(List.of("Workstream", "Description", "Number", "Due date", "Status"),
                List.of("TAX", "Tax returns", "", "2026-12-01", "ignored"),
                List.of("TAX", "Tax assessments", "", "", ""),
                List.of("TAX", "Deed moved to tax", "LEG-001", "", ""));

        var result = service.importList(PROJECT, file, false);

        assertThat(result.committed()).isTrue();
        assertThat(saved).extracting(RequestItem::getNumber).containsExactly("TAX-001", "TAX-002");
        assertThat(saved.getFirst().getDueDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(existing.getWorkstreamId()).isEqualTo(TAX);
        assertThat(existing.getAssigneeUserId()).isNull();
        verify(events).publishEvent(any(RequestListTransferred.class));
    }

    @Test
    void importRespectsWorkstreamScopeAndDuplicates() {
        when(access.editableProject(PROJECT)).thenReturn(scoped(ProjectRole.ANALYST, LEGAL));
        when(items.findByProjectIdOrderByNumber(PROJECT)).thenReturn(List.of());
        var file = workbook(List.of("Number", "Workstream", "Description"),
                List.of("A-1", "TAX", "x"), List.of("A-2", "LEGAL", "x"), List.of("A-2", "LEGAL", "y"));

        var result = service.importList(PROJECT, file, false);

        assertThat(result.committed()).isFalse();
        assertThat(result.rows().get(0).errors()).containsExactly("Workstream TAX is outside your assignment");
        assertThat(result.rows().get(2).errors()).containsExactly("Number A-2 appears more than once");
    }

    @Test
    void rejectsUnreadableOrHeaderlessFiles() {
        assertThatThrownBy(() -> service.importList(PROJECT, new ByteArrayInputStream("nope".getBytes()), true))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("readable");
        assertThatThrownBy(() -> service.importList(PROJECT, workbook(List.of("Foo", "Bar"), List.of("a", "b")),
                true)).hasMessageContaining("column headers");
        assertThatThrownBy(() -> service.importList(PROJECT, workbook(List.of("Workstream", "Description")), true))
                .hasMessageContaining("no request items");
    }

    @SafeVarargs
    private static ByteArrayInputStream workbook(List<String> header, List<String>... rows) {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            var all = new ArrayList<List<String>>();
            all.add(header);
            all.addAll(List.of(rows));
            for (var r = 0; r < all.size(); r++) {
                var row = sheet.createRow(r);
                for (var c = 0; c < all.get(r).size(); c++) {
                    row.createCell(c).setCellValue(all.get(r).get(c));
                }
            }
            workbook.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
