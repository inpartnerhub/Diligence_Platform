package com.inpartnerhub.diligence.shared.events;

import java.util.ArrayList;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;

/**
 * Exposes the event publications that have not completed, per listener and status (issue #81):
 * <ul>
 * <li>{@code diligence.events.publications.incomplete{listener,status}} — FAILED ones wait for the retry job;</li>
 * <li>{@code diligence.events.publications.exhausted{listener}} — FAILED ones the job gave up on.</li>
 * </ul>
 * Every replica reports the same database counts, so alerts use {@code max}, not {@code sum}.
 */
@Component
class EventPublicationMetrics {

    private static final Logger log = LoggerFactory.getLogger(EventPublicationMetrics.class);
    /** {@code com.example.module.internal.SearchIndexer.on(com.example.Event)} → {@code SearchIndexer.on}. */
    private static final Pattern LISTENER = Pattern.compile("(?:.*\\.)?([^.(]+\\.[^.(]+)\\(.*");

    private final JdbcTemplate jdbc;
    private final EventRetryProperties properties;
    private final MultiGauge incomplete;
    private final MultiGauge exhausted;

    EventPublicationMetrics(JdbcTemplate jdbc, EventRetryProperties properties, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.incomplete = MultiGauge.builder("diligence.events.publications.incomplete")
                .description("Event publications not completed yet, by listener and status")
                .register(meters);
        this.exhausted = MultiGauge.builder("diligence.events.publications.exhausted")
                .description("Failed event publications that used up their retries")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${diligence.events.metrics-interval:PT1M}", initialDelay = 0)
    void refresh() {
        try {
            var incompleteRows = new ArrayList<MultiGauge.Row<?>>();
            var exhaustedRows = new ArrayList<MultiGauge.Row<?>>();
            jdbc.query("SELECT listener_id, coalesce(status, 'PUBLISHED') AS status, count(*) AS total, "
                    + "count(*) FILTER (WHERE status = 'FAILED' AND coalesce(completion_attempts, 0) >= ?) "
                    + "AS exhausted FROM public.event_publication WHERE completion_date IS NULL GROUP BY 1, 2",
                    rs -> {
                        var listener = listener(rs.getString("listener_id"));
                        var status = rs.getString("status");
                        incompleteRows.add(MultiGauge.Row.of(Tags.of("listener", listener, "status", status),
                                rs.getLong("total")));
                        if ("FAILED".equals(status)) {
                            exhaustedRows.add(MultiGauge.Row.of(Tags.of("listener", listener),
                                    rs.getLong("exhausted")));
                        }
                    }, properties.maxAttempts());
            // overwrite: listeners without incomplete publications disappear instead of keeping a stale value
            incomplete.register(incompleteRows, true);
            exhausted.register(exhaustedRows, true);
        } catch (RuntimeException ex) {
            log.warn("Could not read event publication counts: {}", ex.getMessage());
        }
    }

    static String listener(String listenerId) {
        var matcher = LISTENER.matcher(listenerId);
        if (!matcher.matches()) {
            return listenerId;
        }
        var name = matcher.group(1);
        return name.substring(name.lastIndexOf('$') + 1); // nested class: Outer$Listener.on → Listener.on
    }
}
