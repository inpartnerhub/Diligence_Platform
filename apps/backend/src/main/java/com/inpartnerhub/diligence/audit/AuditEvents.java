package com.inpartnerhub.diligence.audit;

import java.util.Map;

import com.inpartnerhub.diligence.shared.audit.AuditableEvent;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/** Domain events published by the audit module. */
public final class AuditEvents {

    private AuditEvents() {
    }

    public record AuditLogExported(RequestMetadata requestMetadata, Map<String, String> filters)
            implements AuditableEvent {

        @Override
        public String auditAction() {
            return "audit.exported";
        }

        @Override
        public String auditObjectType() {
            return "audit_log";
        }

        @Override
        public String auditObjectId() {
            return "all";
        }

        @Override
        public Map<String, String> auditDetails() {
            return filters;
        }
    }
}

