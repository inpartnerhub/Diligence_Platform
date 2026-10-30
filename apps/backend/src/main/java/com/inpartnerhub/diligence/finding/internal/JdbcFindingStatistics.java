package com.inpartnerhub.diligence.finding.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.finding.FindingStatistics;
import com.inpartnerhub.diligence.finding.RiskLevel;

@Component
@Transactional(readOnly = true)
class JdbcFindingStatistics implements FindingStatistics {

    static final String UNRATED = "UNRATED";

    private final JdbcTemplate jdbc;

    JdbcFindingStatistics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Long> countsByRiskLevel(UUID projectId) {
        var counts = new LinkedHashMap<String, Long>();
        for (var level : RiskLevel.values()) {
            counts.put(level.name(), 0L);
        }
        counts.put(UNRATED, 0L);
        jdbc.query("""
                select coalesce(risk_level, 'UNRATED') as level, count(*) as total
                from fnd.finding
                where project_id = ? and status <> 'ARCHIVED'
                group by coalesce(risk_level, 'UNRATED')
                """, rs -> {
            counts.put(rs.getString("level"), rs.getLong("total"));
        }, projectId);
        return counts;
    }
}
