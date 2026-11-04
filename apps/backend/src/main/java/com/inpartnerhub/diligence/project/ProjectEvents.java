package com.inpartnerhub.diligence.project;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.inpartnerhub.diligence.shared.audit.AuditableEvent;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/** Domain events of the project module. All of them are audited (PRD ADM-03). */
public final class ProjectEvents {

    private ProjectEvents() {
    }

    /**
     * @param folders       initial data-room folder paths from the template ("Parent/Child"); empty without template
     * @param requestItems  the template's request-list items for the project's workstreams (PRD REQ-01)
     */
    public record ProjectCreated(UUID projectId, String code, List<String> folders,
            List<TemplateRequestItem> requestItems, RequestMetadata requestMetadata) implements AuditableEvent {

        public ProjectCreated {
            folders = folders == null ? List.of() : List.copyOf(folders);
            requestItems = requestItems == null ? List.of() : List.copyOf(requestItems);
        }

        public ProjectCreated(UUID projectId, String code, List<String> folders, RequestMetadata requestMetadata) {
            this(projectId, code, folders, List.of(), requestMetadata);
        }

        @Override
        public String auditAction() {
            return "project.created";
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
            return Map.of("code", code);
        }
    }

    /** A request-list item from a project template, resolved to the new project's workstream. */
    public record TemplateRequestItem(UUID workstreamId, WorkstreamType workstream, String category,
            String description) {
    }

    public record ProjectUpdated(UUID projectId, String change, RequestMetadata requestMetadata)
            implements AuditableEvent {

        @Override
        public String auditAction() {
            return "project." + change;
        }

        @Override
        public String auditObjectType() {
            return "project";
        }

        @Override
        public String auditObjectId() {
            return projectId.toString();
        }
    }

    public record ProjectMemberAdded(UUID projectId, UUID memberId, UUID userId, ProjectRole role,
            RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "project.member-added";
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
            return Map.of("userId", userId.toString(), "role", role.name());
        }
    }

    public record ProjectMemberRemoved(UUID projectId, UUID memberId, UUID userId, RequestMetadata requestMetadata)
            implements AuditableEvent {

        @Override
        public String auditAction() {
            return "project.member-removed";
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
            return Map.of("userId", userId.toString());
        }
    }

    public record ProjectRetentionUpdated(UUID projectId, String code, int retentionPeriodDays,
            RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "project.retention-updated";
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
            return Map.of("code", code, "retentionPeriodDays", String.valueOf(retentionPeriodDays));
        }
    }

    public record ProjectDataPurgeRequested(UUID projectId, String code, RequestMetadata requestMetadata) {
    }

    /** PRD Bab 10.1: the project's IP allow-list for external users changed. */
    public record ProjectIpAllowListUpdated(UUID projectId, String code, int ranges, RequestMetadata requestMetadata)
            implements AuditableEvent {

        @Override
        public String auditAction() {
            return "project.ip-allow-list-updated";
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
            return Map.of("code", code, "ranges", String.valueOf(ranges));
        }
    }

    /**
     * An external user was refused because their address is outside the project's allow-list (PRD Bab 10.1). Kept in
     * the audit log and counted for the suspicious-access alert.
     */
    public record ProjectAccessBlocked(UUID projectId, String code, RequestMetadata requestMetadata)
            implements AuditableEvent {

        @Override
        public String auditAction() {
            return "project.access-blocked";
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
            return Map.of("code", code, "reason", "ip-not-allowed");
        }
    }

    public record ProjectDataPurged(UUID projectId, String code, long documentsCount, long versionsCount,
            long s3ObjectsPurged, RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "project.data-purged";
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
            return Map.of(
                    "code", code,
                    "documentsCount", String.valueOf(documentsCount),
                    "versionsCount", String.valueOf(versionsCount),
                    "s3ObjectsPurged", String.valueOf(s3ObjectsPurged));
        }
    }

    public record TimeEntryCreated(UUID projectId, UUID entryId, UUID userId, WorkstreamType workstreamType,
            BigDecimal hours, LocalDate entryDate, RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "project.time_entry_created";
        }

        @Override
        public String auditObjectType() {
            return "time_entry";
        }

        @Override
        public String auditObjectId() {
            return entryId.toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of(
                    "projectId", projectId.toString(),
                    "workstream", workstreamType.name(),
                    "hours", hours.toPlainString(),
                    "date", entryDate.toString());
        }
    }

    public record TimeEntryUpdated(UUID projectId, UUID entryId, UUID userId, BigDecimal hours,
            RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "project.time_entry_updated";
        }

        @Override
        public String auditObjectType() {
            return "time_entry";
        }

        @Override
        public String auditObjectId() {
            return entryId.toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("projectId", projectId.toString(), "hours", hours.toPlainString());
        }
    }

    public record TimeEntryDeleted(UUID projectId, UUID entryId, UUID userId,
            RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "project.time_entry_deleted";
        }

        @Override
        public String auditObjectType() {
            return "time_entry";
        }

        @Override
        public String auditObjectId() {
            return entryId.toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("projectId", projectId.toString());
        }
    }
}
