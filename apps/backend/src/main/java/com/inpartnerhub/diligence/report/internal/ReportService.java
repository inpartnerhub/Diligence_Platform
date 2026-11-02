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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.admin.Directory.OrganizationSummary;
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
 * Report builder with tiered multi-stage approval workflow (PRD RPT-01, RPT-03):
 * 1. Analyst/PM generates draft report (v1.0) from released findings
 * 2. Analyst submits draft for review
 * 3. Project Manager reviews and approves (enforcing four-eyes principle: author cannot approve own draft)
 * 4. Lead Partner signs off and releases to client
 * 5. Revision tracking preserves prior versions/artifacts and auto-increments major/minor version numbers.
 */
@Service
@Transactional
class ReportService {

    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final int MAX_TITLE = 200;

    enum Format {
        DOCX, PDF
    }

    @FunctionalInterface
    interface Writer {
        void writeTo(OutputStream out) throws IOException;
    }

    record Download(String fileName, String contentType, Writer writer) {
    }

    private final ReportRepository reports;
    private final ReportFindingRepository reportFindings;
    private final ReportStatusHistoryRepository statusHistories;
    private final ProjectDirectory projects;
    private final Directory directory;
    private final FindingDirectory findings;
    private final DocumentStorage storage;
    private final PreviewRenderer renderer;
    private final PdfWatermark watermark;
    private final CurrentUserProvider currentUser;
    private final ApplicationEventPublisher events;
    private final ZoneId zone;
    private final Clock clock;

    @Autowired
    ReportService(ReportRepository reports, ReportFindingRepository reportFindings,
            ReportStatusHistoryRepository statusHistories, ProjectDirectory projects, Directory directory,
            FindingDirectory findings, DocumentStorage storage, PreviewRenderer renderer, PdfWatermark watermark,
            CurrentUserProvider currentUser, ApplicationEventPublisher events,
            @Value("${diligence.dataroom.watermark-zone:Asia/Jakarta}") ZoneId zone) {
        this(reports, reportFindings, statusHistories, projects, directory, findings, storage, renderer, watermark,
                currentUser, events, zone, Clock.systemDefaultZone());
    }

    ReportService(ReportRepository reports, ReportFindingRepository reportFindings,
            ReportStatusHistoryRepository statusHistories, ProjectDirectory projects, Directory directory,
            FindingDirectory findings, DocumentStorage storage, PreviewRenderer renderer, PdfWatermark watermark,
            CurrentUserProvider currentUser, ApplicationEventPublisher events, ZoneId zone, Clock clock) {
        this.reports = reports;
        this.reportFindings = reportFindings;
        this.statusHistories = statusHistories;
        this.projects = projects;
        this.directory = directory;
        this.findings = findings;
        this.storage = storage;
        this.renderer = renderer;
        this.watermark = watermark;
        this.currentUser = currentUser;
        this.events = events;
        this.zone = zone;
        this.clock = clock;
    }

    /** Report versions visible to the caller: the internal team sees all, client sees released reports only. */
    @Transactional(readOnly = true)
    List<Report> list(UUID projectId) {
        project(projectId);
        return reports.findByProjectIdOrderByVersionNumberDesc(projectId);
    }

    @Transactional(readOnly = true)
    List<ReportStatusHistory> history(UUID projectId, UUID reportId) {
        project(projectId);
        report(projectId, reportId);
        return statusHistories.findByProjectIdAndReportIdOrderByChangedAtAsc(projectId, reportId);
    }

    Report generate(UUID projectId, String title, String language) {
        return createReport(projectId, title, language, 1, 0, "v1.0", null);
    }

    Report generateRevision(UUID projectId, UUID previousReportId, String title, boolean major, String language) {
        var prev = report(projectId, previousReportId);
        int newMajor = major ? prev.getMajorVersion() + 1 : prev.getMajorVersion();
        int newMinor = major ? 0 : prev.getMinorVersion() + 1;
        String versionLabel = "v" + newMajor + "." + newMinor;
        String chosenTitle = title == null || title.isBlank() ? prev.getTitle() : title;
        String chosenLang = language == null || language.isBlank() ? prev.getLanguage() : language;
        return createReport(projectId, chosenTitle, chosenLang, newMajor, newMinor, versionLabel,
                "Revision " + versionLabel + " created from " + prev.getVersionLabel());
    }

    private Report createReport(UUID projectId, String title, String language, int majorVersion, int minorVersion,
            String versionLabel, String note) {
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
        var target = Optional.ofNullable(summary.targetOrganizationId()).flatMap(directory::findOrganization)
                .map(OrganizationSummary::name).orElse(null);
        var docx = ReportDocumentWriter.write(new ReportDocumentWriter.Content(summary.code(), summary.name(), target,
                name, versionNumber, lang, now, zone, workstreams, released, findings.riskMatrix()));
        var pdf = toPdf(docx, id + ".docx");
        var base = "projects/%s/reports/%s".formatted(projectId, id);
        storage.put(base + ".docx", new ByteArrayInputStream(docx), docx.length, DOCX);
        try {
            storage.put(base + ".pdf", new ByteArrayInputStream(pdf), pdf.length, "application/pdf");
            var authorId = me();
            var report = reports.saveAndFlush(new Report(id, projectId, null, versionNumber, majorVersion, minorVersion,
                    versionLabel, name, lang, released.size(), base + ".docx", base + ".pdf", pdf.length, authorId));
            for (var i = 0; i < released.size(); i++) {
                reportFindings.save(new ReportFinding(id, released.get(i).id(), projectId, i + 1));
            }
            statusHistories.save(new ReportStatusHistory(UUID.randomUUID(), id, projectId, null, "DRAFT", authorId,
                    note != null ? note : "Initial draft " + versionLabel + " generated"));
            events.publishEvent(new ReportChanged(projectId, context.code(), id, versionNumber, name, "generated",
                    Map.of("findings", String.valueOf(released.size()), "language", lang, "versionLabel", versionLabel),
                    RequestMetadata.capture()));
            return report;
        } catch (RuntimeException ex) {
            storage.delete(base + ".docx");
            storage.delete(base + ".pdf");
            throw ex;
        }
    }

    /** Submit a report draft for manager review (RPT-03). */
    Report submitForReview(UUID projectId, UUID reportId, String note) {
        var context = writable(project(projectId));
        if (!context.hasAnyRole(ProjectRole.PARTNER, ProjectRole.PROJECT_MANAGER, ProjectRole.ANALYST)) {
            throw new AccessDeniedException("Only project team members can submit reports for review");
        }
        var report = report(projectId, reportId);
        if (report.getStatus() != ReportStatus.DRAFT) {
            throw new ResourceConflictException("Only draft reports can be submitted for review");
        }
        var userId = me();
        var fromStatus = report.getStatus().name();
        report.submitForReview(userId, Instant.now(clock));
        statusHistories.save(new ReportStatusHistory(UUID.randomUUID(), reportId, projectId, fromStatus,
                ReportStatus.SUBMITTED_FOR_REVIEW.name(), userId, note));
        events.publishEvent(new ReportChanged(projectId, context.code(), reportId, report.getVersionNumber(),
                report.getTitle(), "submitted_for_review", Map.of(), RequestMetadata.capture()));
        return report;
    }

    /** Project Manager approves the report; enforces four-eyes principle (author cannot approve own draft). */
    Report approveByManager(UUID projectId, UUID reportId, String note) {
        var context = writable(project(projectId));
        if (!context.hasAnyRole(ProjectRole.PARTNER, ProjectRole.PROJECT_MANAGER)) {
            throw new AccessDeniedException("Only project managers and partners can approve reports");
        }
        var report = report(projectId, reportId);
        if (report.getStatus() != ReportStatus.SUBMITTED_FOR_REVIEW) {
            throw new ResourceConflictException("Report must be submitted for review before manager approval");
        }
        var userId = me();
        if (userId.equals(report.getCreatedBy())) {
            throw new InvalidRequestException("Report authors cannot approve their own submissions (four-eyes principle)");
        }
        var fromStatus = report.getStatus().name();
        report.approveByManager(userId, Instant.now(clock));
        statusHistories.save(new ReportStatusHistory(UUID.randomUUID(), reportId, projectId, fromStatus,
                ReportStatus.APPROVED_BY_MANAGER.name(), userId, note));
        events.publishEvent(new ReportChanged(projectId, context.code(), reportId, report.getVersionNumber(),
                report.getTitle(), "manager_approved", Map.of(), RequestMetadata.capture()));
        return report;
    }

    /** Lead Partner releases the report to the client; released reports are strictly immutable. */
    Report releaseByPartner(UUID projectId, UUID reportId, String note) {
        var context = writable(project(projectId));
        if (!context.hasAnyRole(ProjectRole.PARTNER)) {
            throw new AccessDeniedException("Only partners can release reports to the client");
        }
        var report = report(projectId, reportId);
        if (report.getStatus().isReleased()) {
            throw new ResourceConflictException("This report was already released");
        }
        var userId = me();
        var fromStatus = report.getStatus().name();
        report.releaseByPartner(userId, Instant.now(clock));
        statusHistories.save(new ReportStatusHistory(UUID.randomUUID(), reportId, projectId, fromStatus,
                ReportStatus.RELEASED_BY_PARTNER.name(), userId, note));
        events.publishEvent(new ReportChanged(projectId, context.code(), reportId, report.getVersionNumber(),
                report.getTitle(), "released", Map.of(), RequestMetadata.capture()));
        return report;
    }

    /** A partner issues a report (legacy endpoint alias for releaseByPartner). */
    Report issue(UUID projectId, UUID reportId) {
        return releaseByPartner(projectId, reportId, null);
    }

    /** Returns a report in review or approved stage back to DRAFT with a reason note. */
    Report returnDraft(UUID projectId, UUID reportId, String reason) {
        var context = writable(project(projectId));
        if (!context.hasAnyRole(ProjectRole.PARTNER, ProjectRole.PROJECT_MANAGER)) {
            throw new AccessDeniedException("Only project managers and partners can return reports to draft");
        }
        var report = report(projectId, reportId);
        if (report.getStatus().isReleased()) {
            throw new ResourceConflictException("Released reports cannot be returned to draft");
        }
        if (report.getStatus() == ReportStatus.DRAFT) {
            throw new ResourceConflictException("Report is already in draft state");
        }
        if (reason == null || reason.isBlank()) {
            throw new InvalidRequestException("A reason is required when returning a report to draft");
        }
        var userId = me();
        var fromStatus = report.getStatus().name();
        report.returnDraft(Instant.now(clock));
        statusHistories.save(new ReportStatusHistory(UUID.randomUUID(), reportId, projectId, fromStatus,
                ReportStatus.DRAFT.name(), userId, reason.strip()));
        events.publishEvent(new ReportChanged(projectId, context.code(), reportId, report.getVersionNumber(),
                report.getTitle(), "returned_to_draft", Map.of("reason", reason.strip()), RequestMetadata.capture()));
        return report;
    }

    void delete(UUID projectId, UUID reportId) {
        var context = writable(project(projectId));
        if (!context.canManage()) {
            throw new AccessDeniedException("Only partners and project managers delete report drafts");
        }
        var report = report(projectId, reportId);
        if (report.getStatus().isReleased()) {
            throw new ResourceConflictException("Released reports are immutable and cannot be deleted");
        }
        reports.delete(report);
        reports.flush();
        storage.delete(report.getDocxKey());
        storage.delete(report.getPdfKey());
        events.publishEvent(new ReportChanged(projectId, context.code(), reportId, report.getVersionNumber(),
                report.getTitle(), "deleted", Map.of(), RequestMetadata.capture()));
    }

    /**
     * Internal team: Word or PDF as generated. Client: the released PDF, watermarked for them.
     */
    Download download(UUID projectId, UUID reportId, Format format) {
        var context = project(projectId);
        var report = report(projectId, reportId);
        var internal = context.isInternal();
        if (!internal && !report.getStatus().isReleased()) {
            throw new ResourceNotFoundException("Report", reportId);
        }
        var chosen = internal ? format : Format.PDF;
        var versionLabel = report.getVersionLabel() != null ? report.getVersionLabel() : "v" + report.getVersionNumber();
        var base = "%s-%s-%s".formatted(context.code(), "report", versionLabel).toLowerCase(Locale.ROOT);
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
