package com.inpartnerhub.diligence.report;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.inpartnerhub.diligence.shared.audit.AuditableEvent;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/** Audited report events (PRD ADM-03); issuing also notifies the client. */
public final class ReportEvents {

    private ReportEvents() {
    }

    /** @param change  generated, issued, deleted or downloaded */
    public record ReportChanged(UUID projectId, String projectCode, UUID reportId, int versionNumber, String title,
            String change, Map<String, String> details, RequestMetadata requestMetadata) implements AuditableEvent {

        public ReportChanged {
            details = Map.copyOf(details);
        }

        @Override
        public String auditAction() {
            return "report." + change;
        }

        @Override
        public String auditObjectType() {
            return "report";
        }

        @Override
        public String auditObjectId() {
            return reportId.toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            var all = new HashMap<>(details);
            all.put("projectId", projectId.toString());
            all.put("version", String.valueOf(versionNumber));
            return all;
        }
    }
}
