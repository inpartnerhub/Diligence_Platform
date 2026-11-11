package com.inpartnerhub.diligence.admin.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param allowInsecureTargets       accept {@code http://} and private, loopback or link-local target addresses. Only
 *                                   for developer machines (local profile); production must keep the default
 *                                   {@code false} so webhooks cannot reach internal services (SSRF, PRD Bab 10.1).
 * @param keyEncryptionKey           base64 AES-256 key that encrypts webhook signing keys at rest (#64); delivered
 *                                   from Vault as {@code WEBHOOK_KEY_ENCRYPTION_KEY} (ADR-0002)
 * @param previousKeyEncryptionKey   the key before the last rotation, still accepted for reading until the startup
 *                                   job has re-encrypted every signing key with the current one
 * @param logRetention               how long delivery logs (with their payload) are kept; 30 days when not set (#90)
 */
@ConfigurationProperties("diligence.webhooks")
record WebhookProperties(boolean allowInsecureTargets, String keyEncryptionKey, String previousKeyEncryptionKey,
        Duration logRetention) {

    static final Duration DEFAULT_LOG_RETENTION = Duration.ofDays(30);

    WebhookProperties {
        if (logRetention == null || logRetention.isNegative() || logRetention.isZero()) {
            logRetention = DEFAULT_LOG_RETENTION;
        }
    }
}
