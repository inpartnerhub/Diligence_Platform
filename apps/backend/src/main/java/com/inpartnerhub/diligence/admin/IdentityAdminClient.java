package com.inpartnerhub.diligence.admin;

import java.util.List;
import java.util.UUID;

import com.inpartnerhub.diligence.security.Role;

/** Port to the identity provider's admin API (Keycloak). */
public interface IdentityAdminClient {

    /** Creates a disabled-password user and returns its subject id. */
    UUID createUser(NewIdentityUser user);

    /** Makes {@code role} the user's only application realm role. */
    void replaceRole(UUID userId, Role role);

    /** E-mails the user a link to complete the given Keycloak required actions (set password, OTP, NDA, …). */
    void sendRequiredActionsEmail(UUID userId, List<String> requiredActions);

    void setEnabled(UUID userId, boolean enabled);

    /** Compensating action when local provisioning fails after the user was created. */
    void deleteUser(UUID userId);

    record NewIdentityUser(String email, String firstName, String lastName, String locale, UUID organizationId) {
    }
}
