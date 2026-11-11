package com.inpartnerhub.diligence.project.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /**
     * Every project the caller can see, with its key figures (PRD PRJ-05). The figures are loaded for all projects at
     * once, a fixed number of queries however many projects there are (#122), instead of several per project.
     */
    PortfolioDashboard portfolio() {
        var hidden = access.outsideAllowList();
        var allProjects = projects.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .filter(project -> !hidden.contains(project.getId()))
                .toList();
        var today = LocalDate.now(clock);
        var ids = allProjects.stream().map(Project::getId).toList();

        var progressByProject = requests.progressByWorkstream(ids);
        var deadlinesByProject = requests.deadlines(ids, today, DUE_SOON_DAYS);
        var findingsByProject = findings.countsByRiskLevel(ids);
        var workstreamsByProject = workstreams.findByProjectIdInOrderByType(ids).stream()
                .collect(Collectors.groupingBy(Workstream::getProjectId));
        var membersByProject = members.findByProjectIdInOrderByCreatedAt(ids).stream()
                .collect(Collectors.groupingBy(ProjectMember::getProjectId));
        var leads = new LinkedHashMap<UUID, UUID>();
        for (var project : allProjects) {
            membersByProject.getOrDefault(project.getId(), List.of()).stream()
                    .filter(m -> m.getRole() == ProjectRole.PARTNER || m.getRole() == ProjectRole.PROJECT_MANAGER)
                    .findFirst()
                    .ifPresent(m -> leads.put(project.getId(), m.getUserId()));
        }
        var userIds = new LinkedHashSet<UUID>(leads.values());
        allProjects.stream().map(Project::getCreatedBy).forEach(userIds::add);
        var userNames = userIds.isEmpty() ? Map.<UUID, String>of() : directory.findUsers(List.copyOf(userIds))
                .stream().filter(u -> u.displayName() != null)
                .collect(Collectors.toMap(Directory.UserSummary::id, Directory.UserSummary::displayName, (a, b) -> a));
        var organizationNames = new HashMap<UUID, String>();

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

            var counts = progressByProject.getOrDefault(pid, List.of()).stream()
                    .collect(Collectors.toMap(RequestStatistics.WorkstreamProgress::workstreamId,
                            RequestStatistics.WorkstreamProgress::countsByStatus));
            var wsList = workstreamsByProject.getOrDefault(pid, List.of());
            var wsProgress = wsList.stream()
                    .map(w -> progress(w, counts.getOrDefault(w.getId(), Map.of())))
                    .toList();
            long requested = wsProgress.stream().mapToLong(WorkstreamProgress::requested).sum();
            long fulfilled = wsProgress.stream().mapToLong(WorkstreamProgress::fulfilled).sum();
            int completion = percent(fulfilled, requested);

            var deadlines = deadlinesByProject.getOrDefault(pid, new RequestStatistics.DeadlineSummary(0, 0));
            var findingCounts = findingsByProject.getOrDefault(pid, Map.of());
            long critical = findingCounts.getOrDefault("CRITICAL", 0L);
            long high = findingCounts.getOrDefault("HIGH", 0L);
            long totalF = findingCounts.values().stream().mapToLong(Long::longValue).sum();
            long membersCount = membersByProject.getOrDefault(pid, List.of()).size();

            totalFindingsCount += totalF;
            totalCriticalCount += critical;
            totalHighCount += high;
            totalOverdueCount += deadlines.overdue();

            // Organization names are cached (Redis) and shared by many projects: one lookup per organization.
            String clientName = organizationName(project.getClientOrganizationId(), organizationNames);
            String targetName = organizationName(project.getTargetOrganizationId(), organizationNames);
            var lead = leads.get(pid);
            String leadName = lead != null && userNames.containsKey(lead) ? userNames.get(lead)
                    : userNames.get(project.getCreatedBy());

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

    private String organizationName(UUID organizationId, Map<UUID, String> names) {
        if (organizationId == null) {
            return null;
        }
        if (!names.containsKey(organizationId)) {
            names.put(organizationId, directory.findOrganization(organizationId)
                    .map(Directory.OrganizationSummary::name).orElse(null));
        }
        return names.get(organizationId);
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
