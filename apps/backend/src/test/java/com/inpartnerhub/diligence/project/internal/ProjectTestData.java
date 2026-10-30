package com.inpartnerhub.diligence.project.internal;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.inpartnerhub.diligence.admin.Directory.OrganizationSummary;
import com.inpartnerhub.diligence.admin.Directory.OrganizationType;
import com.inpartnerhub.diligence.admin.Directory.UserSummary;
import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.Role;

final class ProjectTestData {

    static final UUID CLIENT_ORG = UUID.randomUUID();
    static final UUID TARGET_ORG = UUID.randomUUID();

    private ProjectTestData() {
    }

    static Project project(UUID createdBy) {
        return new Project("DD-2026-001", "Project Atlas", CLIENT_ORG, TARGET_ORG, LocalDate.of(2026, 11, 1),
                LocalDate.of(2027, 2, 28), null, createdBy);
    }

    static AuthenticatedUser user(Role role) {
        return new AuthenticatedUser(UUID.randomUUID(), "u", "u@x.test", "U", Set.of(role));
    }

    static UserSummary summary(Role role, UUID organizationId) {
        return new UserSummary(UUID.randomUUID(), role.name().toLowerCase() + "@x.test", role.name(), organizationId,
                role, true);
    }

    static Optional<OrganizationSummary> organization(UUID id, OrganizationType type) {
        return Optional.of(new OrganizationSummary(id, type.name() + " Co", type, true));
    }
}
