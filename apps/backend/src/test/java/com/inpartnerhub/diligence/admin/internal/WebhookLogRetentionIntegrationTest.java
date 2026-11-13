package com.inpartnerhub.diligence.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.inpartnerhub.diligence.TestContainersConfiguration;
import com.inpartnerhub.diligence.shared.audit.AuditableEvent;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/**
 * Issue #90 against PostgreSQL with row-level security: delivery logs expire after the retention period, and the
 * logs that mention a purged project go when its purge event is dispatched. Both run as the RLS system role.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only",
        "diligence.webhooks.key-encryption-key=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
        "diligence.webhooks.log-retention=P30D"
})
@Import(TestContainersConfiguration.class)
class WebhookLogRetentionIntegrationTest {

    @Autowired
    WebhookLogRetention retention;

    @Autowired
    WebhookService webhooks;

    /** The schema owner's connection: seeds and checks data outside row-level security (#177). */
    JdbcTemplate jdbc;

    @Autowired
    void ownerDatabase(TestContainersConfiguration.OwnerDatabase owner) {
        jdbc = owner.jdbc();
    }

    @Test
    void deletesLogsOlderThanTheRetentionPeriod() {
        var subscription = subscription();
        var old = log(subscription, "{\"event\":\"x\"}", Instant.now().minus(31, ChronoUnit.DAYS));
        var recent = log(subscription, "{\"event\":\"x\"}", Instant.now().minus(29, ChronoUnit.DAYS));

        retention.deleteExpired();

        assertThat(exists(old)).isFalse();
        assertThat(exists(recent)).isTrue();
    }

    @Test
    void deletesTheLogsOfAPurgedProjectWhenItsPurgeEventIsDispatched() {
        var subscription = subscription();
        var project = UUID.randomUUID();
        var other = UUID.randomUUID();
        var mentioning = log(subscription, "{\"objectId\":\"" + project + "\"}", Instant.now());
        var inDetails = log(subscription, "{\"data\":{\"projectId\":\"" + project + "\"}}", Instant.now());
        var unrelated = log(subscription, "{\"data\":{\"projectId\":\"" + other + "\"}}", Instant.now());

        // A plain instance: the bean is an @Async listener and would return before the delete. The subscription is
        // inactive, so dispatching sends nothing; only the retention part runs.
        new WebhookDispatcher(webhooks, retention).on(new PurgedEvent(project));

        assertThat(exists(mentioning)).isFalse();
        assertThat(exists(inDetails)).isFalse();
        assertThat(exists(unrelated)).isTrue();
    }

    private UUID subscription() {
        var id = UUID.randomUUID();
        jdbc.update("insert into adm.webhook_subscription (id, target_url, signing_key, created_by, active) "
                + "values (?, ?, ?, ?, false)", id, "https://hooks.example.com/" + id, "whsec_test", UUID.randomUUID());
        return id;
    }

    private UUID log(UUID subscription, String payload, Instant deliveredAt) {
        var id = UUID.randomUUID();
        jdbc.update("insert into adm.webhook_delivery_log (id, subscription_id, event_type, payload, success, "
                + "delivered_at) values (?, ?, 'test.event', ?, true, ?)", id, subscription, payload,
                Timestamp.from(deliveredAt));
        return id;
    }

    private boolean exists(UUID id) {
        return jdbc.queryForObject("select count(*) from adm.webhook_delivery_log where id = ?", Integer.class, id) > 0;
    }

    private record PurgedEvent(UUID projectId) implements AuditableEvent {

        @Override
        public String auditAction() {
            return WebhookLogRetention.PROJECT_PURGED;
        }

        @Override
        public String auditObjectType() {
            return "project";
        }

        @Override
        public String auditObjectId() {
            return projectId.toString();
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
