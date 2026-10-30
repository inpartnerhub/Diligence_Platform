package com.inpartnerhub.diligence.project;

import java.util.Map;
import java.util.UUID;

import com.inpartnerhub.diligence.shared.audit.AuditableEvent;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/** Domain events of the project module. All of them are audited (PRD ADM-03). */
public final class ProjectEvents {

    private ProjectEvents() {
    }

    public record ProjectCreated(UUID projectId, String code, RequestMetadata requestMetadata) implements AuditableEvent {

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
