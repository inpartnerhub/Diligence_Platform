package com.inpartnerhub.diligence.request.internal;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.project.RequestStatistics;

@Component
@Transactional(readOnly = true)
class JdbcRequestStatistics implements RequestStatistics {

    private static final String OPEN_STATUSES = "('REQUESTED', 'PARTIAL', 'REJECTED')";

    private final JdbcTemplate jdbc;

    JdbcRequestStatistics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<WorkstreamProgress> progressByWorkstream(UUID projectId) {
        var counts = new LinkedHashMap<UUID, Map<String, Long>>();
        jdbc.query("""
                select workstream_id, status, count(*) as total
                from req.request_item
                where project_id = ?
                group by workstream_id, status
                order by workstream_id
                """, rs -> {
            counts.computeIfAbsent(rs.getObject("workstream_id", UUID.class), k -> new LinkedHashMap<>())
                    .put(rs.getString("status"), rs.getLong("total"));
        }, projectId);
        return counts.entrySet().stream().map(e -> new WorkstreamProgress(e.getKey(), e.getValue())).toList();
    }

    @Override
    public Map<UUID, List<WorkstreamProgress>> progressByWorkstream(Collection<UUID> projectIds) {
        var counts = new LinkedHashMap<UUID, Map<UUID, Map<String, Long>>>();
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        jdbc.query("""
                select project_id, workstream_id, status, count(*) as total
                from req.request_item
                where project_id = any(?)
                group by project_id, workstream_id, status
                order by project_id, workstream_id
                """, ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", projectIds.toArray())), rs -> {
            counts.computeIfAbsent(rs.getObject("project_id", UUID.class), k -> new LinkedHashMap<>())
                    .computeIfAbsent(rs.getObject("workstream_id", UUID.class), k -> new LinkedHashMap<>())
                    .put(rs.getString("status"), rs.getLong("total"));
        });
        var result = new LinkedHashMap<UUID, List<WorkstreamProgress>>();
        counts.forEach((project, byWorkstream) -> result.put(project, byWorkstream.entrySet().stream()
                .map(e -> new WorkstreamProgress(e.getKey(), e.getValue())).toList()));
        return result;
    }

    @Override
    public Map<UUID, DeadlineSummary> deadlines(Collection<UUID> projectIds, LocalDate today, int days) {
        var result = new LinkedHashMap<UUID, DeadlineSummary>();
        if (projectIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                select project_id,
                       count(*) filter (where due_date between ? and ?) as due_soon,
                       count(*) filter (where due_date < ?)             as overdue
                from req.request_item
                where project_id = any(?) and status in %s
                group by project_id
                """.formatted(OPEN_STATUSES), ps -> {
            ps.setObject(1, today);
            ps.setObject(2, today.plusDays(days));
            ps.setObject(3, today);
            ps.setArray(4, ps.getConnection().createArrayOf("uuid", projectIds.toArray()));
        }, rs -> {
            result.put(rs.getObject("project_id", UUID.class),
                    new DeadlineSummary(rs.getLong("due_soon"), rs.getLong("overdue")));
        });
        return result;
    }

    @Override
    public DeadlineSummary deadlines(UUID projectId, LocalDate today, int days) {
        return jdbc.queryForObject("""
                select count(*) filter (where due_date between ? and ?) as due_soon,
                       count(*) filter (where due_date < ?)             as overdue
                from req.request_item
                where project_id = ? and status in %s
                """.formatted(OPEN_STATUSES),
                (rs, row) -> new DeadlineSummary(rs.getLong("due_soon"), rs.getLong("overdue")),
                today, today.plusDays(days), today, projectId);
    }
}
