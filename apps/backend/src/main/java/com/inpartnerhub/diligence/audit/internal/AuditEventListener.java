package com.inpartnerhub.diligence.audit.internal;

import java.time.Clock;
import java.time.Instant;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.shared.audit.AuditableEvent;

import tools.jackson.databind.json.JsonMapper;

@Component
class AuditEventListener {

    private final AuditLogRepository repository;
    private final JsonMapper json;
    private final Clock clock;

    AuditEventListener(AuditLogRepository repository, JsonMapper json) {
        this(repository, json, Clock.systemUTC());
    }

    AuditEventListener(AuditLogRepository repository, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(AuditableEvent event) {
        var metadata = event.requestMetadata();
        repository.save(new AuditLogEntry(
                Instant.now(clock),
                metadata.actorId(),
                event.auditAction(),
                event.auditObjectType(),
                event.auditObjectId(),
                metadata.ipAddress(),
                metadata.userAgent(),
                "SUCCESS",
                json.writeValueAsString(event.auditDetails())));
    }
}
