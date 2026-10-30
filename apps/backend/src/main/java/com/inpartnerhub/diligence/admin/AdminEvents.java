package com.inpartnerhub.diligence.admin;

import java.util.Map;
import java.util.UUID;

import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.audit.AuditableEvent;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/** Domain events published by the admin module. All of them are audited. */
public final class AdminEvents {

    private AdminEvents() {
    }

    public record OrganizationCreated(UUID organizationId, String name, String type, RequestMetadata requestMetadata)
            implements AuditableEvent {

        @Override
        public String auditAction() {
            return "organization.created";
        }

        @Override
        public String auditObjectType() {
            return "organization";
        }

        @Override
        public String auditObjectId() {
            return organizationId.toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("name", name, "type", type);
        }
    }

    public record UserInvited(UUID userId, UUID organizationId, Role role, RequestMetadata requestMetadata)
            implements AuditableEvent {

        @Override
        public String auditAction() {
            return "user.invited";
        }

        @Override
        public String auditObjectType() {
            return "user";
        }

        @Override
        public String auditObjectId() {
            return userId.toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("organizationId", String.valueOf(organizationId), "role", role.name());
        }
    }

    public record UserRoleChanged(UUID userId, Role previousRole, Role newRole, RequestMetadata requestMetadata)
            implements AuditableEvent {

        @Override
        public String auditAction() {
            return "user.role-changed";
        }

        @Override
        public String auditObjectType() {
            return "user";
        }

        @Override
        public String auditObjectId() {
            return userId.toString();
        }

        @Override
        public Map<String, String> auditDetails() {
            return Map.of("previousRole", String.valueOf(previousRole), "newRole", newRole.name());
        }
    }

    public record UserStatusChanged(UUID userId, boolean enabled, RequestMetadata requestMetadata)
            implements AuditableEvent {

        @Override
        public String auditAction() {
            return enabled ? "user.enabled" : "user.disabled";
        }

        @Override
        public String auditObjectType() {
            return "user";
        }

        @Override
        public String auditObjectId() {
            return userId.toString();
        }
    }

    public record UserLoggedIn(UUID userId, RequestMetadata requestMetadata) implements AuditableEvent {

        @Override
        public String auditAction() {
            return "user.login";
        }

        @Override
        public String auditObjectType() {
            return "user";
        }

        @Override
        public String auditObjectId() {
            return userId.toString();
        }
    }
}
