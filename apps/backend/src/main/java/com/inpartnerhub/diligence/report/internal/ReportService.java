package com.inpartnerhub.diligence.report.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.PdfWatermark;
import com.inpartnerhub.diligence.finding.FindingDirectory;
import com.inpartnerhub.diligence.ingestion.PreviewRenderer;
import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.project.ProjectDirectory.WorkstreamSummary;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.report.ReportEvents.ReportChanged;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;
import com.inpartnerhub.diligence.shared.web.UpstreamServiceException;

/**
 * Report builder (PRD RPT-01): partners and project managers generate a new version from the released findings
 * (Word, plus a PDF rendition), a partner issues it to the client. The internal team downloads Word or PDF; the client
 * downloads only issued reports, as PDF watermarked with their identity (DOC-04). Every step is audited.
 */
@Service
@Transactional
class ReportService {

    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final int MAX_TITLE = 200;

    enum Format { DOCX, PDF }

    @FunctionalInterface
    interface Writer {
        void writeTo(OutputStream out) throws IOException;
    }

    record Download(String fileName, String contentType, Writer writer) {
    }

    private final ReportRepository reports;
    private final ReportFindingRepository reportFindings;
    private final ProjectDirectory projects;
    private final FindingDirectory findings;
    private final DocumentStorage storage;
    private final PreviewRenderer renderer;
    private final PdfWatermark watermark;
    private final CurrentUserProvider currentUser;
    private final ApplicationEventPublisher events;
    private final ZoneId zone;
    private final Clock clock;

    @Autowired
    ReportService(ReportRepository reports, ReportFindingRepository reportFindings, ProjectDirectory projects,
            FindingDirectory findings, DocumentStorage storage, PreviewRenderer renderer, PdfWatermark watermark,
            CurrentUserProvider currentUser, ApplicationEventPublisher events,
            @Value("${diligence.dataroom.watermark-zone:Asia/Jakarta}") ZoneId zone) {
        this(reports, reportFindings, projects, findings, storage, renderer, watermark, currentUser, events, zone,
                Clock.systemDefaultZone());
    }

    ReportService(ReportRepository reports, ReportFindingRepository reportFindings, ProjectDirectory projects,
            FindingDirectory findings, DocumentStorage storage, PreviewRenderer renderer, PdfWatermark watermark,
            CurrentUserProvider currentUser, ApplicationEventPublisher events, ZoneId zone, Clock clock) {
        this.reports = reports;
        this.reportFindings = reportFindings;
        this.projects = projects;
        this.findings = findings;
        this.storage = storage;
        this.renderer = renderer;
        this.watermark = watermark;
        this.currentUser = currentUser;
        this.events = events;
        this.zone = zone;
        this.clock = clock;
    }

    /** Report versions visible to the caller: the internal team all, the client issued ones (row-level security). */
    @Transactional(readOnly = true)
    List<Report> list(UUID projectId) {
        project(projectId);
        return reports.findByProjectIdOrderByVersionNumberDesc(projectId);
    }

    Report generate(UUID projectId, String title, String language) {
        var context = writable(project(projectId));
        if (!context.canManage()) {
            throw new AccessDeniedException("Only partners and project managers generate reports");
        }
        var name = title == null || title.isBlank() ? null : title.strip();
        if (name == null || name.length() > MAX_TITLE) {
            throw new InvalidRequestException("Reports need a title of at most 200 characters");
        }
        var lang = language == null ? "id" : language.toLowerCase(Locale.ROOT);
        if (!lang.equals("id") && !lang.equals("en")) {
            throw new InvalidRequestException("Reports are written in Indonesian (id) or English (en)");
        }
        var released = findings.released(projectId, null);
        if (released.isEmpty()) {
            throw new ResourceConflictException("Release at least one finding before generating a report");
        }
        var summary = projects.summary(projectId).orElseThrow();
        var workstreams = projects.workstreams(projectId).stream()
                .collect(Collectors.toMap(WorkstreamSummary::id, WorkstreamSummary::type));
        var id = UUID.randomUUID();
        var versionNumber = reports.lastVersion(projectId) + 1;
        var now = Instant.now(clock);
        var docx = ReportDocumentWriter.write(new ReportDocumentWriter.Content(summary.code(), summary.name(), null,
                name, versionNumber, lang, now, zone, workstreams, released, findings.riskMatrix()));
        var pdf = toPdf(docx, id + ".docx");
        var base = "projects/%s/reports/%s".formatted(projectId, id);
        storage.put(base + ".docx", new ByteArrayInputStream(docx), docx.length, DOCX);
        try {
            storage.put(base + ".pdf", new ByteArrayInputStream(pdf), pdf.length, "application/pdf");
            var report = reports.saveAndFlush(new Report(id, projectId, null, versionNumber, name, lang,
                    released.size(), base + ".docx", base + ".pdf", pdf.length, me()));
            for (var i = 0; i < released.size(); i++) {
                reportFindings.save(new ReportFinding(id, released.get(i).id(), projectId, i + 1));
            }
            events.publishEvent(new ReportChanged(projectId, context.code(), id, versionNumber, name, "generated",
                    Map.of("findings", String.valueOf(released.size()), "language", lang),
                    RequestMetadata.capture()));
            return report;
        } catch (RuntimeException ex) {
            storage.delete(base + ".docx");
            storage.delete(base + ".pdf");
            throw ex;
        }
    }

    /** A partner issues a draft to the client; issued reports never change again (row-level security, V11). */
    Report issue(UUID projectId, UUID reportId) {
        var context = writable(project(projectId));
        if (!context.hasAnyRole(ProjectRole.PARTNER)) {
            throw new AccessDeniedException("Only partners issue reports to the client");
        }
        var report = report(projectId, reportId);
        if (report.getStatus() == ReportStatus.ISSUED) {
            throw new ResourceConflictException("This report was already issued");
        }
        report.issue(me(), Instant.now(clock));
        events.publishEvent(new ReportChanged(projectId, context.code(), reportId, report.getVersionNumber(),
                report.getTitle(), "issued", Map.of(), RequestMetadata.capture()));
        return report;
    }

    void delete(UUID projectId, UUID reportId) {
        var context = writable(project(projectId));
        if (!context.canManage()) {
            throw new AccessDeniedException("Only partners and project managers delete report drafts");
        }
        var report = report(projectId, reportId);
        if (report.getStatus() == ReportStatus.ISSUED) {
            throw new ResourceConflictException("Issued reports are kept");
        }
        reports.delete(report);
        reports.flush();
        storage.delete(report.getDocxKey());
        storage.delete(report.getPdfKey());
        events.publishEvent(new ReportChanged(projectId, context.code(), reportId, report.getVersionNumber(),
                report.getTitle(), "deleted", Map.of(), RequestMetadata.capture()));
    }

    /**
     * Internal team: Word or PDF as generated. Client: the issued PDF, watermarked for them. Always audited (not
     * read-only: the audit event goes to the outbox in this transaction).
     */
    Download download(UUID projectId, UUID reportId, Format format) {
        var context = project(projectId);
        var report = report(projectId, reportId);
        var internal = context.isInternal();
        if (!internal && report.getStatus() != ReportStatus.ISSUED) {
            throw new ResourceNotFoundException("Report", reportId);
        }
        var chosen = internal ? format : Format.PDF;
        var base = "%s-%s-v%d".formatted(context.code(), "report", report.getVersionNumber())
                .toLowerCase(Locale.ROOT);
        events.publishEvent(new ReportChanged(projectId, context.code(), reportId, report.getVersionNumber(),
                report.getTitle(), "downloaded", Map.of("format", chosen.name(), "watermarked",
                        String.valueOf(!internal)), RequestMetadata.capture()));
        if (chosen == Format.DOCX) {
            return new Download(base + ".docx", DOCX, out -> copy(report.getDocxKey(), out));
        }
        if (internal) {
            return new Download(base + ".pdf", "application/pdf", out -> copy(report.getPdfKey(), out));
        }
        return new Download(base + ".pdf", "application/pdf", out -> watermarked(report.getPdfKey(), out));
    }

    // ------------------------------------------------------------------ helpers

    private ProjectContext project(UUID projectId) {
        var context = projects.context(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
        if (!context.isInternal() && !context.hasAnyRole(ProjectRole.CLIENT)) {
            throw new AccessDeniedException("Reports are not available to the target side");
        }
        return context;
    }

    private static ProjectContext writable(ProjectContext context) {
        if (context.archived()) {
            throw new ResourceConflictException("Project " + context.code() + " is archived");
        }
        return context;
    }

    private Report report(UUID projectId, UUID reportId) {
        return reports.findById(reportId).filter(r -> r.getProjectId().equals(projectId))
                .orElseThrow(() -> new ResourceNotFoundException("Report", reportId));
    }

    private byte[] toPdf(byte[] docx, String fileName) {
        try {
            var target = Files.createTempFile("report", ".pdf");
            try {
                renderer.renderPdf(new ByteArrayInputStream(docx), fileName, DOCX, target);
                return Files.readAllBytes(target);
            } finally {
                Files.deleteIfExists(target);
            }
        } catch (IOException | RuntimeException ex) {
            throw new UpstreamServiceException("The PDF rendition is not available right now", ex);
        }
    }

    private void copy(String key, OutputStream out) throws IOException {
        try (InputStream content = storage.get(key)) {
            content.transferTo(out);
        }
    }

    private void watermarked(String key, OutputStream out) throws IOException {
        var file = Files.createTempFile("report-", ".pdf");
        try {
            try (InputStream content = storage.get(key)) {
                Files.copy(content, file, StandardCopyOption.REPLACE_EXISTING);
            }
            watermark.stampForCurrentUser(file, true, out);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private UUID me() {
        return currentUser.requireCurrentUser().id();
    }
}
