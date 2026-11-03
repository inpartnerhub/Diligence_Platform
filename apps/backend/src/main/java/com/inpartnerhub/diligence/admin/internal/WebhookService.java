package com.inpartnerhub.diligence.admin.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.shared.audit.AuditableEvent;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

import tools.jackson.databind.json.JsonMapper;

@Service
class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    record DeliveryResult(boolean success, Integer statusCode, long durationMs, String errorMessage, UUID logId) {
    }

    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryLogRepository deliveryLogs;
    private final CurrentUserProvider currentUser;
    private final JsonMapper json;
    private final HttpClient httpClient;
    private final Clock clock;
    private final long retryBaseDelayMs;

    @Autowired
    WebhookService(WebhookSubscriptionRepository subscriptions, WebhookDeliveryLogRepository deliveryLogs,
            CurrentUserProvider currentUser, JsonMapper json) {
        this(subscriptions, deliveryLogs, currentUser, json,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Clock.systemUTC(), 50L);
    }

    WebhookService(WebhookSubscriptionRepository subscriptions, WebhookDeliveryLogRepository deliveryLogs,
            CurrentUserProvider currentUser, JsonMapper json, HttpClient httpClient, Clock clock, long retryBaseDelayMs) {
        this.subscriptions = subscriptions;
        this.deliveryLogs = deliveryLogs;
        this.currentUser = currentUser;
        this.json = json;
        this.httpClient = httpClient;
        this.clock = clock;
        this.retryBaseDelayMs = retryBaseDelayMs;
    }

    @Transactional(readOnly = true)
    List<WebhookSubscription> list() {
        return subscriptions.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    WebhookSubscription get(UUID id) {
        return subscriptions.findById(id).orElseThrow(() -> new ResourceNotFoundException("WebhookSubscription", id));
    }

    @Transactional
    WebhookSubscription create(UUID organizationId, String targetUrl, String signingKey, String description,
            String subscribedEvents, Boolean active) {
        validateUrl(targetUrl);
        var id = UUID.randomUUID();
        var now = Instant.now(clock);
        var key = signingKey != null && !signingKey.isBlank()
                ? signingKey.strip()
                : "whsec_" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        var sub = new WebhookSubscription(id, organizationId, targetUrl.strip(), key,
                description != null ? description.strip() : null,
                subscribedEvents, active == null || active, me(), now, now);
        return subscriptions.save(sub);
    }

    @Transactional
    WebhookSubscription update(UUID id, String targetUrl, String signingKey, String description,
            String subscribedEvents, Boolean active) {
        var sub = get(id);
        if (targetUrl != null && !targetUrl.isBlank()) {
            validateUrl(targetUrl);
            sub.setTargetUrl(targetUrl.strip());
        }
        if (signingKey != null && !signingKey.isBlank()) {
            sub.setSigningKey(signingKey.strip());
        }
        if (description != null) {
            sub.setDescription(description.strip());
        }
        if (subscribedEvents != null) {
            sub.setSubscribedEvents(subscribedEvents);
        }
        if (active != null) {
            sub.setActive(active);
        }
        sub.setUpdatedAt(Instant.now(clock));
        return subscriptions.save(sub);
    }

    @Transactional
    void delete(UUID id) {
        var sub = get(id);
        subscriptions.delete(sub);
    }

    @Transactional(readOnly = true)
    List<WebhookDeliveryLog> deliveries(UUID subscriptionId) {
        get(subscriptionId);
        return deliveryLogs.findTop25BySubscriptionIdOrderByDeliveredAtDesc(subscriptionId);
    }

    @Transactional
    DeliveryResult testPing(UUID subscriptionId) {
        var sub = get(subscriptionId);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", UUID.randomUUID().toString());
        payload.put("event", "ping");
        payload.put("objectType", "webhook");
        payload.put("objectId", sub.getId().toString());
        payload.put("timestamp", Instant.now(clock).toString());
        payload.put("data", Map.of("message", "Test ping from Diligence Platform", "subscriptionId", sub.getId().toString()));

        String payloadJson = serialize(payload);
        return deliverWithRetry(sub, "ping", payloadJson, 1, 0L);
    }

    @Transactional
    void dispatch(AuditableEvent event) {
        var activeSubs = subscriptions.findByActiveTrue();
        if (activeSubs.isEmpty()) {
            return;
        }

        var action = event.auditAction();
        for (var sub : activeSubs) {
            if (matchesEvent(sub.getSubscribedEvents(), action)) {
                var payload = new LinkedHashMap<String, Object>();
                payload.put("id", UUID.randomUUID().toString());
                payload.put("event", action);
                payload.put("objectType", event.auditObjectType());
                payload.put("objectId", event.auditObjectId());
                payload.put("timestamp", Instant.now(clock).toString());
                payload.put("data", event.auditDetails());

                String payloadJson = serialize(payload);
                try {
                    deliverWithRetry(sub, action, payloadJson, 5, retryBaseDelayMs);
                } catch (Exception ex) {
                    log.error("Failed to dispatch webhook event {} to {}: {}", action, sub.getTargetUrl(), ex.getMessage());
                }
            }
        }
    }

    DeliveryResult deliverWithRetry(WebhookSubscription sub, String eventType, String payloadJson, int maxAttempts,
            long baseDelayMs) {
        int attempts = 0;
        Integer lastStatusCode = null;
        String lastError = null;
        long start = System.currentTimeMillis();

        String signature = calculateSignature(sub.getSigningKey(), payloadJson);
        String deliveryId = UUID.randomUUID().toString();

        while (attempts < maxAttempts) {
            attempts++;
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(sub.getTargetUrl()))
                        .header("Content-Type", "application/json")
                        .header("X-Diligence-Event", eventType)
                        .header("X-Diligence-Signature", signature)
                        .header("X-Diligence-Delivery", deliveryId)
                        .header("User-Agent", "Diligence-Platform-Webhooks/1.0")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                lastStatusCode = response.statusCode();
                if (lastStatusCode >= 200 && lastStatusCode < 300) {
                    long duration = System.currentTimeMillis() - start;
                    var logEntry = deliveryLogs.save(new WebhookDeliveryLog(UUID.randomUUID(), sub.getId(), eventType,
                            payloadJson, lastStatusCode, true, attempts, null, Instant.now(clock), duration));
                    return new DeliveryResult(true, lastStatusCode, duration, null, logEntry.getId());
                }
                lastError = "HTTP " + lastStatusCode + ": " + truncate(response.body(), 250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = "Interrupted: " + e.getMessage();
                break;
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            if (attempts < maxAttempts && baseDelayMs > 0) {
                try {
                    long delay = baseDelayMs * (1L << (attempts - 1));
                    Thread.sleep(Math.min(delay, 2000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        long duration = System.currentTimeMillis() - start;
        var logEntry = deliveryLogs.save(new WebhookDeliveryLog(UUID.randomUUID(), sub.getId(), eventType, payloadJson,
                lastStatusCode, false, attempts, lastError, Instant.now(clock), duration));
        return new DeliveryResult(false, lastStatusCode, duration, lastError, logEntry.getId());
    }

    static boolean matchesEvent(String subscribedEvents, String eventAction) {
        if (subscribedEvents == null || subscribedEvents.isBlank() || subscribedEvents.equals("*")) {
            return true;
        }
        String[] parts = subscribedEvents.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.equals("*") || trimmed.equalsIgnoreCase(eventAction)) {
                return true;
            }
            if (trimmed.endsWith(".*")) {
                String prefix = trimmed.substring(0, trimmed.length() - 2);
                if (eventAction.startsWith(prefix + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    static String calculateSignature(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to calculate HMAC signature", e);
        }
    }

    private String serialize(Object obj) {
        try {
            return json.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize webhook payload to JSON", e);
        }
    }

    private static void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidRequestException("Target URL cannot be empty");
        }
        try {
            var uri = URI.create(url);
            var scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new InvalidRequestException("Target URL must use HTTP or HTTPS protocol");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new InvalidRequestException("Target URL must contain a valid host");
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Invalid target URL format: " + e.getMessage());
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private UUID me() {
        return currentUser.requireCurrentUser().id();
    }
}
