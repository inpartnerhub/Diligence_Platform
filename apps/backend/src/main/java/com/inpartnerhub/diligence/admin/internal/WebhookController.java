package com.inpartnerhub.diligence.admin.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping(AdminApiPaths.ADMIN + "/webhooks")
// Platform-wide events cross every project boundary, so only the Super Admin may configure them (PRD Bab 3).
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Administration")
class WebhookController {

    /**
     * {@code signingKey} is only filled right after the key was generated (create, rotate) so the administrator can
     * copy it once; every other response carries just {@code signingKeyHint}.
     */
    record WebhookResponse(
            UUID id,
            UUID organizationId,
            String targetUrl,
            String signingKey,
            String signingKeyHint,
            String description,
            String subscribedEvents,
            boolean active,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt) {

        static WebhookResponse of(WebhookSubscription s) {
            return of(s, false);
        }

        static WebhookResponse of(WebhookSubscription s, boolean revealSigningKey) {
            return new WebhookResponse(
                    s.getId(),
                    s.getOrganizationId(),
                    s.getTargetUrl(),
                    revealSigningKey ? s.getSigningKey() : null,
                    WebhookService.signingKeyHint(s.getSigningKey()),
                    s.getDescription(),
                    s.getSubscribedEvents(),
                    s.isActive(),
                    s.getCreatedBy(),
                    s.getCreatedAt(),
                    s.getUpdatedAt());
        }
    }

    record CreateWebhookRequest(
            UUID organizationId,
            @NotBlank @Size(max = 1000) String targetUrl,
            @Size(max = 200) String signingKey,
            @Size(max = 500) String description,
            String subscribedEvents,
            Boolean active) {
    }

    record UpdateWebhookRequest(
            @Size(max = 1000) String targetUrl,
            @Size(max = 200) String signingKey,
            @Size(max = 500) String description,
            String subscribedEvents,
            Boolean active) {
    }

    record DeliveryLogResponse(
            UUID id,
            UUID subscriptionId,
            String eventType,
            String payload,
            Integer statusCode,
            boolean success,
            int attemptCount,
            String errorMessage,
            Instant deliveredAt,
            Long durationMs) {

        static DeliveryLogResponse of(WebhookDeliveryLog log) {
            return new DeliveryLogResponse(
                    log.getId(),
                    log.getSubscriptionId(),
                    log.getEventType(),
                    log.getPayload(),
                    log.getStatusCode(),
                    log.isSuccess(),
                    log.getAttemptCount(),
                    log.getErrorMessage(),
                    log.getDeliveredAt(),
                    log.getDurationMs());
        }
    }

    record TestPingResponse(
            boolean success,
            Integer statusCode,
            long durationMs,
            String errorMessage,
            UUID logId) {
    }

    private final WebhookService service;

    WebhookController(WebhookService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List webhook subscriptions — ADM-07")
    List<WebhookResponse> list() {
        return service.list().stream().map(WebhookResponse::of).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get webhook subscription details — ADM-07")
    WebhookResponse get(@PathVariable UUID id) {
        return WebhookResponse.of(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an outbound webhook subscription — ADM-07")
    WebhookResponse create(@Valid @RequestBody CreateWebhookRequest req) {
        return WebhookResponse.of(service.create(
                req.organizationId(),
                req.targetUrl(),
                req.signingKey(),
                req.description(),
                req.subscribedEvents(),
                req.active()), true);
    }

    @PostMapping("/{id}/rotate-signing-key")
    @Operation(summary = "Generate a new signing key; it is returned once — ADM-07")
    WebhookResponse rotateSigningKey(@PathVariable UUID id) {
        return WebhookResponse.of(service.rotateSigningKey(id), true);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update webhook subscription — ADM-07")
    WebhookResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateWebhookRequest req) {
        return WebhookResponse.of(service.update(
                id,
                req.targetUrl(),
                req.signingKey(),
                req.description(),
                req.subscribedEvents(),
                req.active()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete webhook subscription — ADM-07")
    void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Send a test ping event to the webhook target URL — ADM-07")
    TestPingResponse testPing(@PathVariable UUID id) {
        var res = service.testPing(id);
        return new TestPingResponse(res.success(), res.statusCode(), res.durationMs(), res.errorMessage(), res.logId());
    }

    @GetMapping("/{id}/deliveries")
    @Operation(summary = "List recent webhook delivery attempt logs — ADM-07")
    List<DeliveryLogResponse> deliveries(@PathVariable UUID id) {
        return service.deliveries(id).stream().map(DeliveryLogResponse::of).toList();
    }
}

