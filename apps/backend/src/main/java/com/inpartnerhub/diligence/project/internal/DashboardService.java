package com.inpartnerhub.diligence.project.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.project.FindingStatistics;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.project.ProjectStage;
import com.inpartnerhub.diligence.project.RequestStatistics;
import com.inpartnerhub.diligence.project.WorkstreamType;

/**
 * Project dashboard (PRD PRJ-04): request-list progress per workstream, findings by risk level and approaching or
 * overdue deadlines. Every figure is computed under the caller's row-level-security context, so external users
 * only see what their role allows (e.g. no findings for the target side).
 * Also powers the portfolio dashboard (PRD PRJ-05) for cross-project monitoring.
 */
@Service
@Transactional(readOnly = true)
class DashboardService {

    static final int DUE_SOON_DAYS = 7;

    record WorkstreamProgress(UUID workstreamId, WorkstreamType type, long total, long requested, long fulfilled,
            Map<String, Long> byStatus, int completionPercent) {
    }

    record Dashboard(UUID projectId, ProjectStage stage, Project.Status status, int completionPercent,
            List<WorkstreamProgress> workstreams, Map<String, Long> findingsByRisk, long dueSoon, long overdue,
            long memberCount) {
    }

    record PortfolioProjectSummary(UUID projectId, String code, String name, UUID clientOrganizationId,
            String clientName, UUID targetOrganizationId, String targetName, ProjectStage stage,
            Project.Status status, LocalDate startDate, LocalDate endDate, int completionPercent,
            long totalFindings, long criticalFindings, long highFindings, long overdueRequests,
            long dueSoonRequests, long memberCount, List<WorkstreamType> workstreams, String leadName) {
    }

    record PortfolioDashboard(long totalProjects, long activeProjects, long totalFindings,
            long criticalFindings, long highFindings, long totalOverdueRequests,
            Map<String, Long> projectsByStage, List<PortfolioProjectSummary> projects) {
    }

    private final ProjectRepository projects;
    private final ProjectAccess access;
    private final WorkstreamRepository workstreams;
    private final ProjectMemberRepository members;
    private final RequestStatistics requests;
    private final FindingStatistics findings;
    private final Directory directory;
    private final Clock clock;

    @Autowired
    DashboardService(ProjectRepository projects, ProjectAccess access, WorkstreamRepository workstreams,
            ProjectMemberRepository members, RequestStatistics requests, FindingStatistics findings,
            Directory directory) {
        this(projects, access, workstreams, members, requests, findings, directory, Clock.systemDefaultZone());
    }

    DashboardService(ProjectRepository projects, ProjectAccess access, WorkstreamRepository workstreams,
            ProjectMemberRepository members, RequestStatistics requests, FindingStatistics findings,
            Directory directory, Clock clock) {
        this.projects = projects;
        this.access = access;
        this.workstreams = workstreams;
        this.members = members;
        this.requests = requests;
        this.findings = findings;
        this.directory = directory;
        this.clock = clock;
    }

    Dashboard dashboard(UUID projectId) {
        var project = access.visible(projectId);
        var counts = requests.progressByWorkstream(projectId).stream()
                .collect(Collectors.toMap(RequestStatistics.WorkstreamProgress::workstreamId,
                        RequestStatistics.WorkstreamProgress::countsByStatus));
        var progress = workstreams.findByProjectIdOrderByType(projectId).stream()
                .map(w -> progress(w, counts.getOrDefault(w.getId(), Map.of())))
                .toList();
        long requested = progress.stream().mapToLong(WorkstreamProgress::requested).sum();
        long fulfilled = progress.stream().mapToLong(WorkstreamProgress::fulfilled).sum();
        var deadlines = requests.deadlines(projectId, LocalDate.now(clock), DUE_SOON_DAYS);
        return new Dashboard(projectId, project.getStage(), project.getStatus(), percent(fulfilled, requested),
                progress, findings.countsByRiskLevel(projectId), deadlines.dueSoon(), deadlines.overdue(),
                members.countByProjectId(projectId));
    }

    PortfolioDashboard portfolio() {
        var allProjects = projects.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        var today = LocalDate.now(clock);

        List<PortfolioProjectSummary> summaries = new ArrayList<>();
        long activeCount = 0;
        long totalFindingsCount = 0;
        long totalCriticalCount = 0;
        long totalHighCount = 0;
        long totalOverdueCount = 0;
        Map<String, Long> byStage = new LinkedHashMap<>();
        for (ProjectStage stage : ProjectStage.values()) {
            byStage.put(stage.name(), 0L);
        }

        for (Project project : allProjects) {
            UUID pid = project.getId();
            if (project.getStatus() == Project.Status.ACTIVE) {
                activeCount++;
                byStage.compute(project.getStage().name(), (k, v) -> v == null ? 1L : v + 1L);
            }

            var counts = requests.progressByWorkstream(pid).stream()
                    .collect(Collectors.toMap(RequestStatistics.WorkstreamProgress::workstreamId,
                            RequestStatistics.WorkstreamProgress::countsByStatus));
            var wsList = workstreams.findByProjectIdOrderByType(pid);
            var wsProgress = wsList.stream()
                    .map(w -> progress(w, counts.getOrDefault(w.getId(), Map.of())))
                    .toList();
            long requested = wsProgress.stream().mapToLong(WorkstreamProgress::requested).sum();
            long fulfilled = wsProgress.stream().mapToLong(WorkstreamProgress::fulfilled).sum();
            int completion = percent(fulfilled, requested);

            var deadlines = requests.deadlines(pid, today, DUE_SOON_DAYS);
            var findingCounts = findings.countsByRiskLevel(pid);
            long critical = findingCounts.getOrDefault("CRITICAL", 0L);
            long high = findingCounts.getOrDefault("HIGH", 0L);
            long totalF = findingCounts.values().stream().mapToLong(Long::longValue).sum();
            long membersCount = members.countByProjectId(pid);

            totalFindingsCount += totalF;
            totalCriticalCount += critical;
            totalHighCount += high;
            totalOverdueCount += deadlines.overdue();

            String clientName = directory.findOrganization(project.getClientOrganizationId())
                    .map(Directory.OrganizationSummary::name).orElse(null);
            String targetName = directory.findOrganization(project.getTargetOrganizationId())
                    .map(Directory.OrganizationSummary::name).orElse(null);

            var projectMembers = members.findByProjectIdOrderByCreatedAt(pid);
            String leadName = projectMembers.stream()
                    .filter(m -> m.getRole() == ProjectRole.PARTNER || m.getRole() == ProjectRole.PROJECT_MANAGER)
                    .findFirst()
                    .flatMap(m -> directory.findUser(m.getUserId()))
                    .map(Directory.UserSummary::displayName)
                    .or(() -> directory.findUser(project.getCreatedBy()).map(Directory.UserSummary::displayName))
                    .orElse(null);

            List<WorkstreamType> wsTypes = wsList.stream().map(Workstream::getType).toList();

            summaries.add(new PortfolioProjectSummary(
                    pid,
                    project.getCode(),
                    project.getName(),
                    project.getClientOrganizationId(),
                    clientName,
                    project.getTargetOrganizationId(),
                    targetName,
                    project.getStage(),
                    project.getStatus(),
                    project.getStartDate(),
                    project.getEndDate(),
                    completion,
                    totalF,
                    critical,
                    high,
                    deadlines.overdue(),
                    deadlines.dueSoon(),
                    membersCount,
                    wsTypes,
                    leadName));
        }

        return new PortfolioDashboard(
                allProjects.size(),
                activeCount,
                totalFindingsCount,
                totalCriticalCount,
                totalHighCount,
                totalOverdueCount,
                byStage,
                summaries);
    }

    /**
     * Completion = items received or closed, out of items actually requested (everything except
     * NOT_REQUESTED) — the basis of the PRD KPI "80% of the request list fulfilled".
     */
    static WorkstreamProgress progress(Workstream workstream, Map<String, Long> byStatus) {
        Function<String, Long> count = status -> byStatus.getOrDefault(status, 0L);
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long requested = total - count.apply("NOT_REQUESTED");
        long fulfilled = count.apply("RECEIVED") + count.apply("CLOSED");
        return new WorkstreamProgress(workstream.getId(), workstream.getType(), total, requested, fulfilled, byStatus,
                percent(fulfilled, requested));
    }

    static int percent(long part, long whole) {
        return whole == 0 ? 0 : (int) Math.round(100.0 * part / whole);
    }
}
