package com.inpartnerhub.diligence.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.inpartnerhub.diligence.shared.audit.AuditableEvent;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

import tools.jackson.databind.json.JsonMapper;

class AuditEventListenerTest {

    record SampleEvent(UUID id, RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "user.invited";
        }

        @Override
        public String auditObjectType() {
            return "user";
        }

        @Override
        public String auditObjectId() {
            return id.toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("role", "TARGET");
        }
    }

    @Test
    void writesEventToAuditLog() {
        var repository = mock(AuditLogRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var now = Instant.parse("2026-11-01T08:00:00Z");
        var listener = new AuditEventListener(repository, JsonMapper.builder().build(), Clock.fixed(now, ZoneOffset.UTC));
        var actor = UUID.randomUUID();
        var subject = UUID.randomUUID();

        listener.on(new SampleEvent(subject, new RequestMetadata(actor, "10.0.0.7", "Mozilla/5.0")));

        var entry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(repository).save(entry.capture());
        assertThat(entry.getValue().getOccurredAt()).isEqualTo(now);
        assertThat(entry.getValue().getUserId()).isEqualTo(actor);
        assertThat(entry.getValue().getAction()).isEqualTo("user.invited");
        assertThat(entry.getValue().getObjectId()).isEqualTo(subject.toString());
        assertThat(entry.getValue().getIpAddress()).isEqualTo("10.0.0.7");
        assertThat(entry.getValue().getDetails()).isEqualTo("{\"role\":\"TARGET\"}");
    }
}
