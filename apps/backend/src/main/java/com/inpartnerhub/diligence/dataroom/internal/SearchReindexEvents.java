package com.inpartnerhub.diligence.dataroom.internal;

import java.util.Map;
import java.util.UUID;

import com.inpartnerhub.diligence.shared.audit.AuditableEvent;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/** Audited lifecycle of a bulk search re-index (issue #80). */
public final class SearchReindexEvents {

    private SearchReindexEvents() {
    }

    public record SearchReindexStarted(UUID runId, RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "search.reindex_started";
        }

        @Override
        public String auditObjectType() {
            return "search_reindex_run";
        }

        @Override
        public String auditObjectId() {
            return runId.toString();
        }
    }

    public record SearchReindexFinished(UUID runId, String status, long indexed, long failed, Long removed,
            RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "search.reindex_finished";
        }

        @Override
        public String auditObjectType() {
            return "search_reindex_run";
        }

        @Override
        public String auditObjectId() {
            return runId.toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("status", status, "indexed", Long.toString(indexed), "failed", Long.toString(failed),
                    "removed", removed == null ? "skipped" : removed.toString());
        }
    }
}
