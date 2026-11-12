package com.inpartnerhub.diligence.report.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.admin.Directory.OrganizationSummary;
import com.inpartnerhub.diligence.admin.Directory.OrganizationType;
import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.PdfWatermark;
import com.inpartnerhub.diligence.finding.FindingDirectory;
import com.inpartnerhub.diligence.finding.FindingDirectory.ReleasedFinding;
import com.inpartnerhub.diligence.finding.FindingDirectory.Source;
import com.inpartnerhub.diligence.finding.RiskLevel;
import com.inpartnerhub.diligence.finding.RiskMatrix;
import com.inpartnerhub.diligence.ingestion.PreviewRenderer;
import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectSummary;
import com.inpartnerhub.diligence.project.ProjectDirectory.WorkstreamSummary;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.report.ReportEvents.ReportChanged;
import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;
import com.inpartnerhub.diligence.shared.web.UpstreamServiceException;

class ReportServiceTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID LEGAL = UUID.randomUUID();
    private static final UUID TAX = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID TARGET_ORG = UUID.randomUUID();

    private final ReportRepository reports = mock(ReportRepository.class);
    private final ReportFindingRepository reportFindings = mock(ReportFindingRepository.class);
    private final ReportStatusHistoryRepository statusHistories = mock(ReportStatusHistoryRepository.class);
    private final ProjectDirectory projects = mock(ProjectDirectory.class);
    private final Directory directory = mock(Directory.class);
    private final FindingDirectory findings = mock(FindingDirectory.class);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final PreviewRenderer renderer = mock(PreviewRenderer.class);
    private final PdfWatermark watermark = mock(PdfWatermark.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ReportService service = new ReportService(reports, reportFindings, statusHistories, projects, directory, findings,
            storage, renderer, watermark, currentUser, events, ZoneId.of("Asia/Jakarta"),
            Clock.fixed(Instant.parse("2026-12-01T02:00:00Z"), ZoneOffset.UTC), TransactionOperations.withoutTransaction(),
            TransactionOperations.withoutTransaction());

    private final Map<String, byte[]> stored = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        when(currentUser.requireCurrentUser()).thenReturn(new AuthenticatedUser(ME, "me", "me@x.test", "Me",
                Set.of(Role.PARTNER)));
        when(projects.summary(PROJECT))
                .thenReturn(Optional.of(new ProjectSummary(PROJECT, "DD-1", "Alpha", TARGET_ORG, false)));
        when(directory.findOrganization(TARGET_ORG)).thenReturn(Optional.of(
                new OrganizationSummary(TARGET_ORG, "PT Target Sejahtera", OrganizationType.TARGET, true)));
        when(projects.workstreams(PROJECT)).thenReturn(List.of(new WorkstreamSummary(LEGAL, WorkstreamType.LEGAL),
                new WorkstreamSummary(TAX, WorkstreamType.TAX)));
        when(findings.riskMatrix()).thenReturn(RiskMatrix.DEFAULT);
        when(reports.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            stored.put(inv.getArgument(0), ((InputStream) inv.getArgument(1)).readAllBytes());
            return null;
        }).when(storage).put(anyString(), any(), anyLong(), anyString());
        when(storage.get(anyString())).thenAnswer(inv -> new ByteArrayInputStream(stored.get(inv.getArgument(0))));
        doAnswer(inv -> Files.write((Path) inv.getArgument(3), "%PDF-report".getBytes()))
                .when(renderer).renderPdf(any(), anyString(), eq(ReportService.DOCX), any());
    }

    private void as(ProjectRole role) {
        when(projects.context(PROJECT)).thenReturn(Optional.of(new ProjectContext(PROJECT, "DD-1", false,
                Set.of(role), Set.of())));
    }

    private static ReleasedFinding finding(UUID workstream, String number, int impact, int likelihood) {
        var rating = RiskMatrix.DEFAULT.rate(impact, likelihood);
        return new ReleasedFinding(UUID.randomUUID(), number, workstream, "Title " + number, "Assets",
                "Line one\nLine two", "Price adjustment", new BigDecimal("2500000000"), "IDR", "Condition precedent",
                impact, likelihood, rating.score(), rating.level(), List.of(new Source("akta.pdf", "p. 12")));
    }

    @Test
    void generatesWordAndPdfFromReleasedFindings() throws Exception {
        as(ProjectRole.PROJECT_MANAGER);
        when(reports.lastVersion(PROJECT)).thenReturn(2);
        when(findings.released(PROJECT, null)).thenReturn(List.of(finding(LEGAL, "F-LEG-001", 5, 4),
                finding(TAX, "F-TAX-001", 2, 2)));

        var report = service.generate(PROJECT, " Draft DD report ", "id");

        assertThat(report.getVersionNumber()).isEqualTo(3);
        assertThat(report.getTitle()).isEqualTo("Draft DD report");
        assertThat(report.getFindingCount()).isEqualTo(2);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(stored).containsKeys(report.getDocxKey(), report.getPdfKey());
        try (var docx = new XWPFDocument(new ByteArrayInputStream(stored.get(report.getDocxKey())));
                var text = new XWPFWordExtractor(docx)) {
            var content = text.getText();
            assertThat(content).contains("Draft DD report", "DD-1 · Alpha", "Target: PT Target Sejahtera", "Versi 3",
                    "Ringkasan eksekutif", "Laporan ini memuat 2 temuan", "Risiko utama",
                    "F-LEG-001 Title F-LEG-001 (Kritis)", "Hukum",
                    "Perpajakan", "akta.pdf, p. 12", "IDR 2.500.000.000", "Kritis (5 × 4 = 20)", "Line two");
            assertThat(docx.getTables()).hasSize(2); // summary and heat map
        }
        verify(reportFindings, org.mockito.Mockito.times(2)).save(any());
        var event = ArgumentCaptor.forClass(ReportChanged.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().auditAction()).isEqualTo("report.generated");
        assertThat(event.getValue().auditDetails()).containsEntry("findings", "2").containsEntry("version", "3");
    }

    @Test
    void englishReports() throws Exception {
        as(ProjectRole.PARTNER);
        when(findings.released(PROJECT, null)).thenReturn(List.of(finding(LEGAL, "F-LEG-001", 1, 1)));
        var report = service.generate(PROJECT, "Report", "EN");
        try (var docx = new XWPFDocument(new ByteArrayInputStream(stored.get(report.getDocxKey())));
                var text = new XWPFWordExtractor(docx)) {
            assertThat(text.getText()).contains("Executive summary", "Legal", "Low (1 × 1 = 1)")
                    .doesNotContain("Key risks");
        }
        assertThat(ReportDocumentWriter.money(null, "IDR", Locale.ENGLISH)).isNull();
    }

    @Test
    void generationRules() {
        as(ProjectRole.ANALYST);
        assertThatThrownBy(() -> service.generate(PROJECT, "x", "id")).isInstanceOf(AccessDeniedException.class);
        as(ProjectRole.PROJECT_MANAGER);
        assertThatThrownBy(() -> service.generate(PROJECT, " ", "id")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.generate(PROJECT, "x", "de")).isInstanceOf(InvalidRequestException.class);
        when(findings.released(PROJECT, null)).thenReturn(List.of());
        assertThatThrownBy(() -> service.generate(PROJECT, "x", "id")).hasMessageContaining("Release");
        as(ProjectRole.TARGET);
        assertThatThrownBy(() -> service.list(PROJECT)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void failedPdfConversionStoresNothing() throws Exception {
        as(ProjectRole.PROJECT_MANAGER);
        when(findings.released(PROJECT, null)).thenReturn(List.of(finding(LEGAL, "F-LEG-001", 3, 3)));
        doThrow(new IllegalStateException("down")).when(renderer).renderPdf(any(), anyString(), anyString(), any());
        assertThatThrownBy(() -> service.generate(PROJECT, "x", "id")).isInstanceOf(UpstreamServiceException.class);
        verify(storage, never()).put(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void partnersIssueAndIssuedReportsAreKept() {
        var report = new Report(UUID.randomUUID(), PROJECT, null, 1, "R", "id", 1, "k.docx", "k.pdf", 10, ME);
        when(reports.findById(report.getId())).thenReturn(Optional.of(report));
        as(ProjectRole.PROJECT_MANAGER);
        assertThatThrownBy(() -> service.issue(PROJECT, report.getId())).isInstanceOf(AccessDeniedException.class);

        as(ProjectRole.PARTNER);
        service.issue(PROJECT, report.getId());
        assertThat(report.getStatus().isReleased()).isTrue();
        assertThat(report.getIssuedBy()).isEqualTo(ME);
        assertThatThrownBy(() -> service.issue(PROJECT, report.getId())).isInstanceOf(ResourceConflictException.class);
        assertThatThrownBy(() -> service.delete(PROJECT, report.getId()))
                .isInstanceOf(ResourceConflictException.class);

        var draft = new Report(UUID.randomUUID(), PROJECT, null, 2, "R", "id", 1, "d.docx", "d.pdf", 10, ME);
        when(reports.findById(draft.getId())).thenReturn(Optional.of(draft));
        service.delete(PROJECT, draft.getId());
        verify(reports).delete(draft);
        verify(storage).delete("d.docx");
        verify(storage).delete("d.pdf");
    }

    @Test
    void tieredApprovalWorkflowAndFourEyesPrinciple() {
        var author = UUID.randomUUID();
        var report = new Report(UUID.randomUUID(), PROJECT, null, 1, 1, 0, "v1.0", "Draft", "id", 1, "k.docx",
                "k.pdf", 10, author);
        when(reports.findById(report.getId())).thenReturn(Optional.of(report));

        // 1. Analyst submits draft for review
        as(ProjectRole.ANALYST);
        service.submitForReview(PROJECT, report.getId(), "Ready for review");
        assertThat(report.getStatus()).isEqualTo(ReportStatus.SUBMITTED_FOR_REVIEW);
        assertThat(report.getSubmittedBy()).isEqualTo(ME);
        verify(statusHistories).save(any());

        // 2. Author cannot approve own submission (four-eyes principle)
        var authorReport = new Report(UUID.randomUUID(), PROJECT, null, 1, 1, 0, "v1.0", "Draft", "id", 1, "k.docx",
                "k.pdf", 10, ME);
        authorReport.submitForReview(ME, Instant.now());
        when(reports.findById(authorReport.getId())).thenReturn(Optional.of(authorReport));
        as(ProjectRole.PROJECT_MANAGER);
        assertThatThrownBy(() -> service.approveByManager(PROJECT, authorReport.getId(), "Approved"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("four-eyes principle");

        // 3. Manager approves report
        when(reports.findById(report.getId())).thenReturn(Optional.of(report));
        service.approveByManager(PROJECT, report.getId(), "Looks good to me");
        assertThat(report.getStatus()).isEqualTo(ReportStatus.APPROVED_BY_MANAGER);
        assertThat(report.getManagerApprovedBy()).isEqualTo(ME);

        // 4. Non-partner cannot release report
        as(ProjectRole.PROJECT_MANAGER);
        assertThatThrownBy(() -> service.releaseByPartner(PROJECT, report.getId(), "Sign off"))
                .isInstanceOf(AccessDeniedException.class);

        // 5. Lead partner releases report
        as(ProjectRole.PARTNER);
        service.releaseByPartner(PROJECT, report.getId(), "Approved for client distribution");
        assertThat(report.getStatus()).isEqualTo(ReportStatus.RELEASED_BY_PARTNER);
        assertThat(report.getIssuedBy()).isEqualTo(ME);
        assertThat(report.getStatus().isReleased()).isTrue();
    }

    @Test
    void returnToDraftWithReason() {
        var author = UUID.randomUUID();
        var report = new Report(UUID.randomUUID(), PROJECT, null, 1, 1, 0, "v1.0", "Draft", "id", 1, "k.docx",
                "k.pdf", 10, author);
        report.submitForReview(author, Instant.now());
        when(reports.findById(report.getId())).thenReturn(Optional.of(report));

        as(ProjectRole.PROJECT_MANAGER);
        assertThatThrownBy(() -> service.returnDraft(PROJECT, report.getId(), "  "))
                .isInstanceOf(InvalidRequestException.class);

        service.returnDraft(PROJECT, report.getId(), "Need more details in tax finding");
        assertThat(report.getStatus()).isEqualTo(ReportStatus.DRAFT);
    }

    @Test
    void majorAndMinorRevisionGeneration() {
        as(ProjectRole.PROJECT_MANAGER);
        when(reports.lastVersion(PROJECT)).thenReturn(1);
        when(findings.released(PROJECT, null)).thenReturn(List.of(finding(LEGAL, "F-LEG-001", 3, 3)));
        var baseReport = new Report(UUID.randomUUID(), PROJECT, null, 1, 1, 0, "v1.0", "Initial", "id", 1, "k.docx",
                "k.pdf", 10, UUID.randomUUID());
        when(reports.findById(baseReport.getId())).thenReturn(Optional.of(baseReport));

        // Minor revision
        var minor = service.generateRevision(PROJECT, baseReport.getId(), "Rev 1.1", false, "id");
        assertThat(minor.getMajorVersion()).isEqualTo(1);
        assertThat(minor.getMinorVersion()).isEqualTo(1);
        assertThat(minor.getVersionLabel()).isEqualTo("v1.1");
        assertThat(minor.getStatus()).isEqualTo(ReportStatus.DRAFT);
        when(reports.findById(minor.getId())).thenReturn(Optional.of(minor));
        var minorDownload = service.download(PROJECT, minor.getId(), ReportService.Format.PDF);
        assertThat(minorDownload.fileName()).isEqualTo("dd-1-report-v1.1.pdf");

        // Major revision
        var major = service.generateRevision(PROJECT, baseReport.getId(), "Rev 2.0", true, "id");
        assertThat(major.getMajorVersion()).isEqualTo(2);
        assertThat(major.getMinorVersion()).isEqualTo(0);
        assertThat(major.getVersionLabel()).isEqualTo("v2.0");
    }

    @Test
    void statusHistoryAuditTrail() {
        as(ProjectRole.ANALYST);
        var reportId = UUID.randomUUID();
        when(reports.findById(reportId)).thenReturn(Optional.of(new Report(reportId, PROJECT, null, 1, "R", "id", 1,
                "k.docx", "k.pdf", 10, ME)));
        var history = new ReportStatusHistory(UUID.randomUUID(), reportId, PROJECT, "DRAFT", "SUBMITTED_FOR_REVIEW",
                ME, "Ready");
        when(statusHistories.findByProjectIdAndReportIdOrderByChangedAtAsc(PROJECT, reportId))
                .thenReturn(List.of(history));

        var list = service.history(PROJECT, reportId);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getToStatus()).isEqualTo("SUBMITTED_FOR_REVIEW");
    }

    @Test
    void clientsDownloadIssuedReportsWatermarkedAndTheTeamGetsOriginals() throws Exception {
        stored.put("r.docx", "DOCX".getBytes());
        stored.put("r.pdf", "%PDF".getBytes());
        var report = new Report(UUID.randomUUID(), PROJECT, null, 4, "R", "id", 1, "r.docx", "r.pdf", 4, ME);
        when(reports.findById(report.getId())).thenReturn(Optional.of(report));
        doAnswer(inv -> {
            ((OutputStream) inv.getArgument(2)).write("WATERMARKED".getBytes());
            return null;
        }).when(watermark).stampForCurrentUser(any(), anyBoolean(), any());

        as(ProjectRole.CLIENT);
        assertThatThrownBy(() -> service.download(PROJECT, report.getId(), ReportService.Format.PDF))
                .isInstanceOf(ResourceNotFoundException.class);
        report.issue(ME, Instant.now());
        var forClient = service.download(PROJECT, report.getId(), ReportService.Format.DOCX);
        assertThat(forClient.fileName()).isEqualTo("dd-1-report-v4.pdf");
        assertThat(bytes(forClient)).isEqualTo("WATERMARKED");

        as(ProjectRole.ANALYST);
        assertThat(bytes(service.download(PROJECT, report.getId(), ReportService.Format.DOCX))).isEqualTo("DOCX");
        assertThat(bytes(service.download(PROJECT, report.getId(), ReportService.Format.PDF))).isEqualTo("%PDF");
        assertThat(service.list(PROJECT)).isEmpty();

        // PPTX download
        when(findings.released(PROJECT, null)).thenReturn(List.of(finding(LEGAL, "LEG-001", 5, 4)));
        var pptxDownload = service.download(PROJECT, report.getId(), ReportService.Format.PPTX);
        assertThat(pptxDownload.fileName()).isEqualTo("dd-1-report-v4.pptx");
        assertThat(pptxDownload.contentType()).isEqualTo(ReportService.PPTX);
        var pptxBytes = new ByteArrayOutputStream();
        pptxDownload.writer().writeTo(pptxBytes);
        assertThat(pptxBytes.size()).isGreaterThan(0);
        try (var ppt = new org.apache.poi.xslf.usermodel.XMLSlideShow(new ByteArrayInputStream(pptxBytes.toByteArray()))) {
            assertThat(ppt.getSlides()).isNotEmpty();
        }
    }

    private static String bytes(ReportService.Download download) throws Exception {
        var out = new ByteArrayOutputStream();
        download.writer().writeTo(out);
        return out.toString();
    }

    @Test
    void generatesFromTheSelectedReleasedFindingsOnly() {
        as(ProjectRole.PROJECT_MANAGER);
        var legal = finding(LEGAL, "F-LEG-001", 5, 4);
        var tax = finding(TAX, "F-TAX-001", 2, 2);
        when(findings.released(PROJECT, null)).thenReturn(List.of(legal, tax));

        var report = service.generate(PROJECT, "Selected", "id", List.of(tax.id()));

        assertThat(report.getFindingCount()).isEqualTo(1);
        var saved = ArgumentCaptor.forClass(ReportFinding.class);
        verify(reportFindings).save(saved.capture());
        assertThat(saved.getValue().getFindingId()).isEqualTo(tax.id());

        assertThatThrownBy(() -> service.generate(PROJECT, "x", "id", List.of(UUID.randomUUID())))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("released findings");
        assertThatThrownBy(() -> service.generate(PROJECT, "x", "id", List.of()))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("at least one");
    }

    @Test
    void powerPointContainsTheFindingsOfThatReportVersion() throws Exception {
        as(ProjectRole.PARTNER);
        var included = finding(LEGAL, "F-LEG-001", 5, 4);
        var releasedLater = finding(TAX, "F-TAX-009", 2, 2);
        when(findings.released(PROJECT, null)).thenReturn(List.of(included, releasedLater));
        var report = new Report(UUID.randomUUID(), PROJECT, null, 4, "R", "id", 1, "r.docx", "r.pdf", 4, ME);
        when(reports.findById(report.getId())).thenReturn(Optional.of(report));
        when(reportFindings.findByReportIdOrderByPositionAsc(report.getId()))
                .thenReturn(List.of(new ReportFinding(report.getId(), included.id(), PROJECT, 1)));

        var out = new ByteArrayOutputStream();
        service.download(PROJECT, report.getId(), ReportService.Format.PPTX).writer().writeTo(out);

        try (var ppt = new org.apache.poi.xslf.usermodel.XMLSlideShow(new ByteArrayInputStream(out.toByteArray()))) {
            var text = new StringBuilder();
            ppt.getSlides().forEach(slide -> slide.getShapes().forEach(shape -> {
                if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape textShape) {
                    text.append(textShape.getText()).append('\n');
                }
            }));
            assertThat(text.toString()).contains("F-LEG-001").doesNotContain("F-TAX-009");
        }
        var xlsx = service.download(PROJECT, report.getId(), ReportService.Format.XLSX);
        assertThat(xlsx.fileName()).isEqualTo("dd-1-report-v4.xlsx");
        var xlsxBytes = new ByteArrayOutputStream();
        xlsx.writer().writeTo(xlsxBytes);
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new ByteArrayInputStream(xlsxBytes.toByteArray()))) {
            var findingsSheet = workbook.getSheetAt(0);
            assertThat(findingsSheet.getLastRowNum()).isEqualTo(1);
            assertThat(findingsSheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("F-LEG-001");
            assertThat(findingsSheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("Kritis");
        }

        as(ProjectRole.CLIENT); // the client only ever gets the issued, watermarked PDF
        report.issue(ME, Instant.now());
        assertThat(service.download(PROJECT, report.getId(), ReportService.Format.XLSX).fileName()).endsWith(".pdf");
    }

    @Test
    void severityOrderInTheSummary() {
        assertThat(ReportDocumentWriter.COLORS).containsKeys(RiskLevel.values());
    }
}
