package com.inpartnerhub.diligence.admin.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param allowInsecureTargets accept {@code http://} and private, loopback or link-local target addresses. Only for
 *                             developer machines (local profile); production must keep the default {@code false} so
 *                             webhooks cannot reach internal services (SSRF, PRD Bab 10.1).
 */
@ConfigurationProperties("diligence.webhooks")
record WebhookProperties(boolean allowInsecureTargets) {
}
