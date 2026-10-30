package com.inpartnerhub.diligence.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RoleTest {

    @Test
    void externalUsersAndAdminsRequireMfa() {
        var mfaRoles = Arrays.stream(Role.values()).filter(Role::requiresMfa).toList();

        assertThat(mfaRoles).containsExactlyInAnyOrder(Role.SUPER_ADMIN, Role.CLIENT, Role.TARGET, Role.OBSERVER);
    }

    @Test
    void onlyClientTargetAndObserverAreExternal() {
        assertThat(Arrays.stream(Role.values()).filter(Role::isExternal))
                .containsExactlyInAnyOrder(Role.CLIENT, Role.TARGET, Role.OBSERVER);
    }

    @Test
    void mapsRealmRolesAndAuthorities() {
        assertThat(Role.PROJECT_MANAGER.realmRoleName()).isEqualTo("project_manager");
        assertThat(Role.PROJECT_MANAGER.authority()).isEqualTo("ROLE_PROJECT_MANAGER");
        assertThat(Role.fromRealmRole("target")).contains(Role.TARGET);
        assertThat(Role.fromAuthority("ROLE_ANALYST")).contains(Role.ANALYST);
        assertThat(Role.fromRealmRole("offline_access")).isEmpty();
        assertThat(Role.fromAuthority("ROLE_UNKNOWN")).isEmpty();
    }

    @Test
    void primaryRoleIsTheMostPrivileged() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "u", "u@x.test", "U", Set.of(Role.ANALYST, Role.PARTNER));

        assertThat(user.primaryRole()).contains(Role.PARTNER);
        assertThat(user.hasRole(Role.ANALYST)).isTrue();
        assertThat(user.hasRole(Role.SUPER_ADMIN)).isFalse();
    }
}
