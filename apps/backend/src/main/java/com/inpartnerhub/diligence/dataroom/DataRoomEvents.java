package com.inpartnerhub.diligence.dataroom;

import java.util.Map;
import java.util.UUID;

import com.inpartnerhub.diligence.shared.audit.AuditableEvent;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/** Audited data-room events (PRD ADM-03: who uploaded or changed what, when and from where). */
public final class DataRoomEvents {

    private DataRoomEvents() {
    }

    public record DocumentVersionAdded(UUID projectId, UUID documentId, UUID versionId, int versionNumber,
            String fileName, String sha256, RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "document.uploaded";
        }

        @Override
        public String auditObjectType() {
            return "document";
        }

        @Override
        public String auditObjectId() {
            return documentId.toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("projectId", projectId.toString(), "version", String.valueOf(versionNumber),
                    "fileName", fileName, "sha256", sha256);
        }
    }

    public record DocumentScanned(UUID projectId, UUID documentId, UUID versionId, ScanResults.Outcome outcome,
            String detail, RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "document.scan-" + outcome.name().toLowerCase(java.util.Locale.ROOT);
        }

        @Override
        public String auditObjectType() {
            return "document";
        }

        @Override
        public String auditObjectId() {
            return documentId.toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return detail == null ? Map.of("versionId", versionId.toString())
                    : Map.of("versionId", versionId.toString(), "detail", detail);
        }
    }

    public record FolderChanged(UUID projectId, UUID folderId, String change, RequestMetadata requestMetadata)
            implements AuditableEvent {

        @Override
        public String auditAction() {
            return "folder." + change;
        }

        @Override
        public String auditObjectType() {
            return "folder";
        }

        @Override
        public String auditObjectId() {
            return folderId.toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("projectId", projectId.toString());
        }
    }
}
