package com.inpartnerhub.diligence.shared.audit;

import java.util.Map;

/**
 * Domain event that must leave a trace in the immutable audit log (PRD ADM-03). The audit module listens to this
 * type generically, so publishing modules never depend on it.
 */
public interface AuditableEvent {

    /** Verb in {@code <object>.<action>} form, e.g. {@code user.invited}. */
    String auditAction();

    String auditObjectType();

    String auditObjectId();

    RequestMetadata requestMetadata();

    /** Additional non-sensitive context. Never include secrets, tokens or document content. */
    default Map<String, String> auditDetails() {
        return Map.of();
    }
}
