package com.inpartnerhub.diligence.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.inpartnerhub.diligence.TestContainersConfiguration;
import com.inpartnerhub.diligence.shared.audit.AuditableEvent;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/**
 * Issue #115: once every listener has handled an event, its publications are deleted instead of being kept with
 * their serialized payload (completion mode DELETE).
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only"
})
@Import(TestContainersConfiguration.class)
class CompletedEventPublicationIntegrationTest {

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    /** The schema owner's connection: seeds and checks data outside row-level security (#177). */
    JdbcTemplate jdbc;

    @Autowired
    void ownerDatabase(TestContainersConfiguration.OwnerDatabase owner) {
        jdbc = owner.jdbc();
    }

    @Test
    void completedPublicationsAreDeleted() {
        var marker = "completion-test-" + UUID.randomUUID();

        transactions.executeWithoutResult(status -> events.publishEvent(new MarkerEvent(marker)));

        // The audit listener (and the webhook dispatcher) handled it ...
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from audit.audit_log where object_id = ?",
                        Integer.class, marker)).isEqualTo(1));
        // ... and no publication of it remains, completed or not.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from public.event_publication "
                        + "where strpos(serialized_event, ?) > 0", Integer.class, marker)).isZero());
        assertThat(jdbc.queryForObject("select count(*) from public.event_publication "
                + "where completion_date is not null", Integer.class)).isZero();
    }

    record MarkerEvent(String marker) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "test.completion-mode";
        }

        @Override
        public String auditObjectType() {
            return "test";
        }

        @Override
        public String auditObjectId() {
            return marker;
        }

        @Override
        public RequestMetadata requestMetadata() {
            return RequestMetadata.SYSTEM;
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of();
        }
    }
}
