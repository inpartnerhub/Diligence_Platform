package com.inpartnerhub.diligence.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.inpartnerhub.diligence.TestContainersConfiguration;

/**
 * Issue #64 against PostgreSQL: signing keys are written encrypted through JPA, read back in plain text, and legacy
 * plain-text rows are encrypted by the startup job.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only",
        "diligence.webhooks.key-encryption-key=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="
})
@Import(TestContainersConfiguration.class)
class WebhookKeyEncryptionIntegrationTest {

    @Autowired
    WebhookSubscriptionRepository subscriptions;

    @Autowired
    WebhookKeyMigration migration;

    /** The schema owner's connection: seeds and checks data outside row-level security (#177). */
    JdbcTemplate jdbc;

    @Autowired
    void ownerDatabase(TestContainersConfiguration.OwnerDatabase owner) {
        jdbc = owner.jdbc();
    }

    @Test
    void storesSigningKeysEncryptedAndMigratesPlainTextRows() {
        var saved = subscriptions.save(new WebhookSubscription(UUID.randomUUID(), null, "https://hooks.example.com/a",
                "whsec_new_secret", null, "*", true, UUID.randomUUID(), java.time.Instant.now(),
                java.time.Instant.now()));
        assertThat(raw(saved.getId())).startsWith(WebhookKeyCipher.PREFIX).doesNotContain("whsec_new_secret");
        assertThat(subscriptions.findById(saved.getId())).get()
                .extracting(WebhookSubscription::getSigningKey).isEqualTo("whsec_new_secret");

        var legacy = UUID.randomUUID();
        jdbc.update("insert into adm.webhook_subscription (id, target_url, signing_key, created_by) values (?, ?, ?, ?)",
                legacy, "https://hooks.example.com/b", "whsec_legacy_plain", UUID.randomUUID());
        assertThat(subscriptions.findById(legacy)).get()
                .extracting(WebhookSubscription::getSigningKey).isEqualTo("whsec_legacy_plain"); // still readable

        migration.reencrypt();

        assertThat(raw(legacy)).startsWith(WebhookKeyCipher.PREFIX).doesNotContain("whsec_legacy_plain");
        assertThat(subscriptions.findById(legacy)).get()
                .extracting(WebhookSubscription::getSigningKey).isEqualTo("whsec_legacy_plain");
        var before = raw(saved.getId());
        migration.reencrypt(); // idempotent: already current
        assertThat(raw(saved.getId())).isEqualTo(before);
    }

    private String raw(UUID id) {
        return jdbc.queryForObject("select signing_key from adm.webhook_subscription where id = ?", String.class, id);
    }
}
