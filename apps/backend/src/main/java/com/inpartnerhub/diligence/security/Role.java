package com.inpartnerhub.diligence.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Global roles from PRD Bab 3. The Keycloak realm role name is the lower-case enum name; Spring authorities are
 * {@code ROLE_<NAME>}. Project- and workstream-level roles are stored per membership in the project module.
 */
public enum Role {

    SUPER_ADMIN(false, true),
    PARTNER(false, false),
    PROJECT_MANAGER(false, false),
    ANALYST(false, false),
    CLIENT(true, true),
    TARGET(true, true),
    OBSERVER(true, true);

    private final boolean external;
    private final boolean mfaRequired;

    Role(boolean external, boolean mfaRequired) {
        this.external = external;
        this.mfaRequired = mfaRequired;
    }

    /** External users (client, target, auditor) must accept the NDA on first login (PRD 10.2). */
    public boolean isExternal() {
        return external;
    }

    /** MFA is mandatory for external users and administrators (PRD ADM-02). */
    public boolean requiresMfa() {
        return mfaRequired;
    }

    public String realmRoleName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String authority() {
        return "ROLE_" + name();
    }

    public static Optional<Role> fromRealmRole(String realmRole) {
        return Arrays.stream(values()).filter(r -> r.realmRoleName().equals(realmRole)).findFirst();
    }

    public static Optional<Role> fromAuthority(String authority) {
        return Arrays.stream(values()).filter(r -> r.authority().equals(authority)).findFirst();
    }
}
