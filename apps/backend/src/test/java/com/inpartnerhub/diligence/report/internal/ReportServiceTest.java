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
    private final ProjectDirectory projects = mock(ProjectDirectory.class);
    private final Directory directory = mock(Directory.class);
    private final FindingDirectory findings = mock(FindingDirectory.class);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final PreviewRenderer renderer = mock(PreviewRenderer.class);
    private final PdfWatermark watermark = mock(PdfWatermark.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ReportService service = new ReportService(reports, reportFindings, projects, directory, findings,
            storage, renderer, watermark, currentUser, events, ZoneId.of("Asia/Jakarta"),
            Clock.fixed(Instant.parse("2026-12-01T02:00:00Z"), ZoneOffset.UTC));

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
        assertThat(report.getStatus()).isEqualTo(ReportStatus.ISSUED);
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
    }

    private static String bytes(ReportService.Download download) throws Exception {
        var out = new ByteArrayOutputStream();
        download.writer().writeTo(out);
        return out.toString();
    }

    @Test
    void severityOrderInTheSummary() {
        assertThat(ReportDocumentWriter.COLORS).containsKeys(RiskLevel.values());
    }
}
