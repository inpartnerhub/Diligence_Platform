package com.inpartnerhub.diligence.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.Role;

/** #152: the trusted {@code system} context is only for work without a user, never for a user without a role. */
class RowLevelSecurityRoleTest {

    @Test
    void workWithoutAUserRunsAsSystem() {
        assertThat(RowLevelSecurityTransactionManager.roleFor(Optional.empty())).isEqualTo("system");
    }

    @Test
    void aUserRunsWithTheirMostPrivilegedRole() {
        assertThat(RowLevelSecurityTransactionManager.roleFor(Optional.of(user(Set.of(Role.CLIENT, Role.OBSERVER)))))
                .isEqualTo("client");
    }

    @Test
    void aUserWithoutARoleNeverRunsAsSystem() {
        assertThat(RowLevelSecurityTransactionManager.roleFor(Optional.of(user(Set.of())))).isEqualTo("none");
    }

    private static AuthenticatedUser user(Set<Role> roles) {
        return new AuthenticatedUser(UUID.randomUUID(), "u", "u@example.com", "U", roles);
    }
}
