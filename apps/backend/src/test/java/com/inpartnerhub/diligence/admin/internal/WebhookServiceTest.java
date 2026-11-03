package com.inpartnerhub.diligence.admin.internal;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.audit.AuditableEvent;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookServiceTest {

    private final WebhookSubscriptionRepository subscriptions = mock(WebhookSubscriptionRepository.class);
    private final WebhookDeliveryLogRepository deliveryLogs = mock(WebhookDeliveryLogRepository.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final HttpClient httpClient = mock(HttpClient.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC);

    private WebhookService service;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(currentUser.requireCurrentUser()).thenReturn(new AuthenticatedUser(USER_ID, "admin", "admin@x.test", "Admin", Set.of(Role.SUPER_ADMIN)));
        when(subscriptions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(deliveryLogs.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new WebhookService(subscriptions, deliveryLogs, currentUser, json, httpClient, clock, 0L);
    }

    @Test
    void createsSubscriptionWithGeneratedSecretAndValidatesUrl() {
        var sub = service.create(ORG_ID, "https://api.example.com/webhook", null, "Audit hook", "finding.*,report.issued", true);

        assertThat(sub.getTargetUrl()).isEqualTo("https://api.example.com/webhook");
        assertThat(sub.getSigningKey()).startsWith("whsec_");
        assertThat(sub.getDescription()).isEqualTo("Audit hook");
        assertThat(sub.getSubscribedEvents()).isEqualTo("finding.*,report.issued");
        assertThat(sub.isActive()).isTrue();

        assertThatThrownBy(() -> service.create(ORG_ID, "ftp://invalid-url.com", null, null, null, true))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("HTTP or HTTPS");

        assertThatThrownBy(() -> service.create(ORG_ID, "", null, null, null, true))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void updatesAndDeletesSubscription() {
        var id = UUID.randomUUID();
        var existing = new WebhookSubscription(id, ORG_ID, "https://old.url/hook", "key123", "Old", "*", true,
                USER_ID, Instant.now(clock), Instant.now(clock));
        when(subscriptions.findById(id)).thenReturn(Optional.of(existing));

        service.update(id, "https://new.url/hook", "newkey", "New desc", "finding.released", false);
        assertThat(existing.getTargetUrl()).isEqualTo("https://new.url/hook");
        assertThat(existing.getSigningKey()).isEqualTo("newkey");
        assertThat(existing.isActive()).isFalse();

        service.delete(id);
        verify(subscriptions).delete(existing);
    }

    @Test
    void matchesEventCorrectly() {
        assertThat(WebhookService.matchesEvent("*", "finding.status-changed")).isTrue();
        assertThat(WebhookService.matchesEvent(null, "finding.status-changed")).isTrue();
        assertThat(WebhookService.matchesEvent("finding.*", "finding.status-changed")).isTrue();
        assertThat(WebhookService.matchesEvent("finding.released, report.issued", "report.issued")).isTrue();
        assertThat(WebhookService.matchesEvent("finding.*, report.*", "request.submitted")).isFalse();
    }

    @Test
    void calculatesValidHmacSha256Signature() {
        String testKey = "test-hmac-key";
        String payload = "{\"test\":true}";
        String sig = WebhookService.calculateSignature(testKey, payload);

        assertThat(sig).startsWith("sha256=");
        assertThat(sig).hasSize(71); // "sha256=" (7 chars) + 64 hex chars
    }

    @Test
    void deliversSuccessfullyOnHttp200() throws Exception {
        var id = UUID.randomUUID();
        var sub = new WebhookSubscription(id, ORG_ID, "https://api.example.com/hook", "testkey", "Desc", "*", true,
                USER_ID, Instant.now(clock), Instant.now(clock));

        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("OK");
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());

        var result = service.deliverWithRetry(sub, "test.event", "{\"data\":123}", 3, 0L);
        assertThat(result.success()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);

        ArgumentCaptor<WebhookDeliveryLog> captor = ArgumentCaptor.forClass(WebhookDeliveryLog.class);
        verify(deliveryLogs).save(captor.capture());
        assertThat(captor.getValue().isSuccess()).isTrue();
        assertThat(captor.getValue().getAttemptCount()).isEqualTo(1);
    }

    @Test
    void retriesUpToMaxAttemptsOnHttp500() throws Exception {
        var id = UUID.randomUUID();
        var sub = new WebhookSubscription(id, ORG_ID, "https://api.example.com/hook", "testkey", "Desc", "*", true,
                USER_ID, Instant.now(clock), Instant.now(clock));

        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn("Internal Server Error");
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());

        var result = service.deliverWithRetry(sub, "test.event", "{\"data\":123}", 5, 0L);
        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isEqualTo(500);

        verify(httpClient, times(5)).send(any(), any());
        ArgumentCaptor<WebhookDeliveryLog> captor = ArgumentCaptor.forClass(WebhookDeliveryLog.class);
        verify(deliveryLogs).save(captor.capture());
        assertThat(captor.getValue().isSuccess()).isFalse();
        assertThat(captor.getValue().getAttemptCount()).isEqualTo(5);
    }

    @Test
    void dispatchesMatchingEventsToActiveSubscriptions() throws Exception {
        var sub1 = new WebhookSubscription(UUID.randomUUID(), ORG_ID, "https://api.example.com/sub1", "key1", "Sub1",
                "finding.*", true, USER_ID, Instant.now(clock), Instant.now(clock));
        var sub2 = new WebhookSubscription(UUID.randomUUID(), ORG_ID, "https://api.example.com/sub2", "key2", "Sub2",
                "report.issued", true, USER_ID, Instant.now(clock), Instant.now(clock));

        when(subscriptions.findByActiveTrue()).thenReturn(List.of(sub1, sub2));

        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("OK");
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());

        AuditableEvent event = new AuditableEvent() {
            @Override
            public String auditAction() {
                return "finding.status-changed";
            }

            @Override
            public String auditObjectType() {
                return "finding";
            }

            @Override
            public String auditObjectId() {
                return UUID.randomUUID().toString();
            }

            @Override
            public RequestMetadata requestMetadata() {
                return RequestMetadata.capture();
            }

            @Override
            public Map<String, String> auditDetails() {
                return Map.of("key", "val");
            }
        };

        service.dispatch(event);

        // Only sub1 matches "finding.*"
        verify(httpClient, times(1)).send(any(), any());
    }

    @Test
    void testPingSendsPayload() throws Exception {
        var id = UUID.randomUUID();
        var sub = new WebhookSubscription(id, ORG_ID, "https://api.example.com/hook", "testkey", "Desc", "*", true,
                USER_ID, Instant.now(clock), Instant.now(clock));
        when(subscriptions.findById(id)).thenReturn(Optional.of(sub));

        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"received\":true}");
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());

        var result = service.testPing(id);
        assertThat(result.success()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);
    }
}
