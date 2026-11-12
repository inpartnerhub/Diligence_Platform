package com.inpartnerhub.diligence.admin;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.inpartnerhub.diligence.security.Role;

/** Read-only lookups of organizations and users for other modules (e.g. project membership rules). */
public interface Directory {

    Optional<OrganizationSummary> findOrganization(UUID id);

    Optional<UserSummary> findUser(UUID id);

    List<UserSummary> findUsers(List<UUID> ids);

    /** Serializable: cached in Redis by {@code findOrganization} (issue #60). */
    record OrganizationSummary(UUID id, String name, OrganizationType type, boolean active,
            int defaultRetentionDays) implements Serializable {

        public OrganizationSummary(UUID id, String name, OrganizationType type, boolean active) {
            this(id, name, type, active, 365);
        }
    }

    /** {@code role} is {@code null} for an SSO account that has no application role yet (#152). */
    record UserSummary(UUID id, String email, String displayName, UUID organizationId, Role role, boolean active) {
    }

    enum OrganizationType { CONSULTANT, CLIENT, TARGET }
}
