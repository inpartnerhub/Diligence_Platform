package com.inpartnerhub.diligence.admin.internal;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * At startup, encrypts signing keys still stored as plain text and re-encrypts those under the previous
 * key-encryption key after a rotation (issue #64). Idempotent; replicas starting together update the same rows to
 * equivalent values.
 */
@Component
class WebhookKeyMigration {

    private static final Logger log = LoggerFactory.getLogger(WebhookKeyMigration.class);

    private final JdbcTemplate jdbc;
    private final WebhookKeyCipher cipher;

    WebhookKeyMigration(JdbcTemplate jdbc, WebhookKeyCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void reencrypt() {
        var rows = jdbc.queryForList("select id, signing_key from adm.webhook_subscription");
        if (!cipher.configured()) {
            if (!rows.isEmpty()) {
                log.error("WEBHOOK_KEY_ENCRYPTION_KEY is not configured: {} webhook signing keys stay as stored and "
                        + "new webhooks cannot be saved", rows.size());
            }
            return;
        }
        var updated = 0;
        for (var row : rows) {
            var stored = (String) row.get("signing_key");
            if (cipher.needsReencryption(stored)) {
                jdbc.update("update adm.webhook_subscription set signing_key = ? where id = ?",
                        cipher.encrypt(cipher.decrypt(stored)), (UUID) row.get("id"));
                updated++;
            }
        }
        if (updated > 0) {
            log.info("Encrypted {} webhook signing keys with the current key-encryption key", updated);
        }
    }
}
