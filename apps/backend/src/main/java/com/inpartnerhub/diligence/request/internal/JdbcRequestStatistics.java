package com.inpartnerhub.diligence.request.internal;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.request.RequestStatistics;

@Component
@Transactional(readOnly = true)
class JdbcRequestStatistics implements RequestStatistics {

    private static final String OPEN_STATUSES = "('REQUESTED', 'PARTIAL')";

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
