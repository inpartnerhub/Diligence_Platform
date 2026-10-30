package com.inpartnerhub.diligence.project.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.finding.FindingStatistics;
import com.inpartnerhub.diligence.project.ProjectStage;
import com.inpartnerhub.diligence.project.RequestStatistics;
import com.inpartnerhub.diligence.project.WorkstreamType;

/**
 * Project dashboard (PRD PRJ-04): request-list progress per workstream, findings by risk level and approaching or
 * overdue deadlines. Every figure is computed under the caller's row-level-security context, so external users
 * only see what their role allows (e.g. no findings for the target side).
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

    private final ProjectAccess access;
    private final WorkstreamRepository workstreams;
    private final ProjectMemberRepository members;
    private final RequestStatistics requests;
    private final FindingStatistics findings;
    private final Clock clock;

    @Autowired
    DashboardService(ProjectAccess access, WorkstreamRepository workstreams, ProjectMemberRepository members,
            RequestStatistics requests, FindingStatistics findings) {
        this(access, workstreams, members, requests, findings, Clock.systemDefaultZone());
    }

    DashboardService(ProjectAccess access, WorkstreamRepository workstreams, ProjectMemberRepository members,
            RequestStatistics requests, FindingStatistics findings, Clock clock) {
        this.access = access;
        this.workstreams = workstreams;
        this.members = members;
        this.requests = requests;
        this.findings = findings;
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
