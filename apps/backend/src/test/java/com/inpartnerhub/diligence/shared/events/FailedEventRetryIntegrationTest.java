package com.inpartnerhub.diligence.shared.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.inpartnerhub.diligence.TestContainersConfiguration;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Issue #81 against real PostgreSQL: a listener that fails leaves its publication FAILED; the retry job completes it
 * without a restart, gives up after max-attempts, and the metrics show both states.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only",
        "diligence.events.retry.min-age=0s",
        "diligence.events.retry.max-attempts=3",
        // The scheduled runs stay out of the way; the test calls the job itself.
        "diligence.events.retry.interval=1h",
        "diligence.events.metrics-interval=1h"
})
@Import({ TestContainersConfiguration.class, FailedEventRetryIntegrationTest.Listeners.class })
class FailedEventRetryIntegrationTest {

    /** Published by the test; the listeners fail while their counter of remaining failures is above zero. */
    public record Probe(UUID id, String kind) {
    }

    static final AtomicInteger FLAKY_FAILURES = new AtomicInteger();
    static final AtomicInteger FLAKY_CALLS = new AtomicInteger();
    static final AtomicInteger BROKEN_CALLS = new AtomicInteger();

    @TestConfiguration(proxyBeanMethods = false)
    static class Listeners {

        @Bean
        FlakyListener flakyListener() {
            return new FlakyListener();
        }

        @Bean
        BrokenListener brokenListener() {
            return new BrokenListener();
        }
    }

    static class FlakyListener {

        @ApplicationModuleListener
        void on(Probe probe) {
            if (!"flaky".equals(probe.kind())) {
                return;
            }
            FLAKY_CALLS.incrementAndGet();
            if (FLAKY_FAILURES.getAndDecrement() > 0) {
                throw new IllegalStateException("downstream unavailable");
            }
        }
    }

    static class BrokenListener {

        @ApplicationModuleListener
        void on(Probe probe) {
            if (!"broken".equals(probe.kind())) {
                return;
            }
            BROKEN_CALLS.incrementAndGet();
            throw new IllegalStateException("always failing");
        }
    }

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FailedEventRetryJob job;

    @Autowired
    EventPublicationMetrics metrics;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void reset() {
        FLAKY_FAILURES.set(0);
        FLAKY_CALLS.set(0);
        BROKEN_CALLS.set(0);
    }

    @Test
    void retriesAFailedListenerWithoutARestart() {
        FLAKY_FAILURES.set(1); // the first call fails, like the indexer while OpenSearch is down
        var probe = publish("flaky");
        await(() -> "FAILED".equals(status(probe, "FlakyListener")));

        metrics.refresh();
        assertThat(gauge("diligence.events.publications.incomplete", "FlakyListener.on", "FAILED")).isEqualTo(1);

        job.run();

        // Completed publications are deleted (completion-mode: delete, #115): the retried one disappears.
        await(() -> FLAKY_CALLS.get() == 2 && status(probe, "FlakyListener") == null);
        assertThat(FLAKY_CALLS).hasValue(2);
        metrics.refresh();
        assertThat(meters.find("diligence.events.publications.incomplete").tag("listener", "FlakyListener.on")
                .gauge()).isNull();
    }

    @Test
    void givesUpAfterMaxAttemptsAndReportsIt() {
        var probe = publish("broken");
        await(() -> "FAILED".equals(status(probe, "BrokenListener")));

        // max-attempts is 3: the first call plus two retries.
        for (var expected = 2; expected <= 3; expected++) {
            var calls = expected;
            job.run();
            await(() -> BROKEN_CALLS.get() == calls && "FAILED".equals(status(probe, "BrokenListener")));
        }
        // Then the job leaves it alone.
        job.run();
        job.run();
        sleep(1000);

        assertThat(BROKEN_CALLS).hasValue(3);
        assertThat(attempts(probe, "BrokenListener")).isEqualTo(3);
        metrics.refresh();
        assertThat(meters.find("diligence.events.publications.exhausted").tag("listener", "BrokenListener.on")
                .gauge().value()).isEqualTo(1);
    }

    private Probe publish(String kind) {
        var probe = new Probe(UUID.randomUUID(), kind);
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> events.publishEvent(probe));
        return probe;
    }

    private String status(Probe probe, String listener) {
        return jdbc.query("SELECT status FROM public.event_publication WHERE listener_id LIKE ? "
                + "AND serialized_event LIKE ?", (rs, n) -> rs.getString(1), "%" + listener + "%",
                "%" + probe.id() + "%").stream().findFirst().orElse(null);
    }

    private int attempts(Probe probe, String listener) {
        return jdbc.queryForObject("SELECT completion_attempts FROM public.event_publication WHERE listener_id LIKE ? "
                + "AND serialized_event LIKE ?", Integer.class, "%" + listener + "%", "%" + probe.id() + "%");
    }

    private double gauge(String name, String listener, String status) {
        var gauge = meters.find(name).tag("listener", listener).tag("status", status).gauge();
        return gauge == null ? 0 : gauge.value();
    }

    private static void await(Supplier<Boolean> condition) {
        var deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (!condition.get()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within 20 s");
            }
            sleep(100);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
