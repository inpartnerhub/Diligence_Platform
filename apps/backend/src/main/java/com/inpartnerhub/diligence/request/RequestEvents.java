package com.inpartnerhub.diligence.request;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.inpartnerhub.diligence.shared.audit.AuditableEvent;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/** Audited request-list events (PRD ADM-03); they also drive notifications (REQ-04). */
public final class RequestEvents {

    private RequestEvents() {
    }

    /** Snapshot of an item for listeners that run after the transaction (notifications). */
    public record ItemRef(UUID itemId, String number, String description, UUID workstreamId, UUID assigneeUserId) {
    }

    public record RequestItemChanged(UUID projectId, ItemRef item, String change, RequestMetadata requestMetadata)
            implements AuditableEvent {

        @Override
        public String auditAction() {
            return "request." + change;
        }

        @Override
        public String auditObjectType() {
            return "request-item";
        }

        @Override
        public String auditObjectId() {
            return item.itemId().toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("projectId", projectId.toString(), "number", item.number());
        }
    }

    /** Items became visible to the target (REQ-06 staged release). */
    public record RequestItemsReleased(UUID projectId, String projectCode, int wave, List<ItemRef> items,
            RequestMetadata requestMetadata) implements AuditableEvent {

        public RequestItemsReleased {
            items = List.copyOf(items);
        }

        @Override
        public String auditAction() {
            return "request.released";
        }

        @Override
        public String auditObjectType() {
            return "project";
        }

        @Override
        public String auditObjectId() {
            return projectId.toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("wave", String.valueOf(wave), "count", String.valueOf(items.size()));
        }
    }

    /**
     * @param byTarget  whether the target side made the change (delivered documents) rather than the internal team
     * @param reason    rejection reason shown to the target; {@code null} otherwise
     */
    public record RequestStatusChanged(UUID projectId, String projectCode, ItemRef item, String fromStatus,
            String toStatus, boolean byTarget, String reason, RequestMetadata requestMetadata)
            implements AuditableEvent {

        @Override
        public String auditAction() {
            return "request.status-changed";
        }

        @Override
        public String auditObjectType() {
            return "request-item";
        }

        @Override
        public String auditObjectId() {
            return item.itemId().toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("projectId", projectId.toString(), "number", item.number(), "from", fromStatus,
                    "to", toStatus);
        }
    }

    public record RequestDocumentLinked(UUID projectId, ItemRef item, UUID documentId, boolean linked,
            RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return linked ? "request.document-linked" : "request.document-unlinked";
        }

        @Override
        public String auditObjectType() {
            return "request-item";
        }

        @Override
        public String auditObjectId() {
            return item.itemId().toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("projectId", projectId.toString(), "documentId", documentId.toString());
        }
    }

    /** @param internal  internal notes are never shown or announced to the target side */
    public record RequestCommentAdded(UUID projectId, String projectCode, ItemRef item, UUID commentId,
            UUID authorId, boolean byTarget, boolean internal, RequestMetadata requestMetadata)
            implements AuditableEvent {

        @Override
        public String auditAction() {
            return "request.commented";
        }

        @Override
        public String auditObjectType() {
            return "request-item";
        }

        @Override
        public String auditObjectId() {
            return item.itemId().toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("projectId", projectId.toString(), "internal", String.valueOf(internal));
        }
    }

    /** Excel import or Excel/PDF export of a project's request list (REQ-01, REQ-05). */
    public record RequestListTransferred(UUID projectId, String direction, String format, int itemCount,
            RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "request." + direction;
        }

        @Override
        public String auditObjectType() {
            return "project";
        }

        @Override
        public String auditObjectId() {
            return projectId.toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("format", format, "items", String.valueOf(itemCount));
        }
    }
}
