package com.inpartnerhub.diligence.finding.internal;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.finding.FindingEvents.RiskMatrixChanged;
import com.inpartnerhub.diligence.finding.RiskMatrix;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

/**
 * The configured risk matrix thresholds (PRD FND-03, Appendix A defaults). Changing them re-rates every open finding
 * (released and archived findings keep their level) and is audited.
 */
@Service
@Transactional
class RiskMatrixService {

    private final JdbcTemplate jdbc;
    private final CurrentUserProvider currentUser;
    private final ApplicationEventPublisher events;

    RiskMatrixService(JdbcTemplate jdbc, CurrentUserProvider currentUser, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.events = events;
    }

    @Transactional(readOnly = true)
    RiskMatrix current() {
        return jdbc.query("select medium_from, high_from, critical_from from fnd.risk_matrix_config where id = 1",
                rs -> rs.next() ? new RiskMatrix(rs.getInt(1), rs.getInt(2), rs.getInt(3)) : RiskMatrix.DEFAULT);
    }

    /** @return the number of open findings that were re-rated */
    int update(int mediumFrom, int highFrom, int criticalFrom) {
        RiskMatrix matrix;
        try {
            matrix = new RiskMatrix(mediumFrom, highFrom, criticalFrom);
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestException(ex.getMessage());
        }
        jdbc.update("""
                update fnd.risk_matrix_config
                set medium_from = ?, high_from = ?, critical_from = ?, updated_by = ?, updated_at = now()
                where id = 1
                """, matrix.mediumFrom(), matrix.highFrom(), matrix.criticalFrom(),
                currentUser.requireCurrentUser().id());
        var rerated = jdbc.queryForObject("select fnd.recompute_risk_levels()", Integer.class);
        var count = rerated == null ? 0 : rerated;
        events.publishEvent(new RiskMatrixChanged(matrix, count, RequestMetadata.capture()));
        return count;
    }
}
