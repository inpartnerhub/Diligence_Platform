package com.inpartnerhub.diligence.audit.internal;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

/** Insert and read only — no update or delete methods are exposed (append-only by design). */
interface AuditLogRepository extends Repository<AuditLogEntry, UUID>, JpaSpecificationExecutor<AuditLogEntry> {

    AuditLogEntry save(AuditLogEntry entry);
}
