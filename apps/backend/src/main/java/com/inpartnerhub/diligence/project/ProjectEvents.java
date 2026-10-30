package com.inpartnerhub.diligence.project;

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
}
