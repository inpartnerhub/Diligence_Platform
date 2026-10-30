package com.inpartnerhub.diligence.admin.internal;

import java.time.Instant;
import java.util.UUID;

import com.inpartnerhub.diligence.security.Role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Application-side mirror of a Keycloak user. {@code id} is the Keycloak subject. */
@Entity
@Table(schema = "adm", name = "user_account")
class UserAccount {

    enum Status { INVITED, ACTIVE, DISABLED }

    @Id
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "global_role", nullable = false)
    private Role globalRole;

    @Column(nullable = false)
    private String locale;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected UserAccount() {
    }

    private UserAccount(UUID id, UUID organizationId, String email, String displayName, Role role, String locale,
            Status status) {
        this.id = id;
        this.organizationId = organizationId;
        this.email = email;
        this.displayName = displayName;
        this.globalRole = role;
        this.locale = locale;
        this.status = status;
        this.mfaEnabled = role.requiresMfa();
        this.createdAt = Instant.now();
    }

    static UserAccount invited(UUID id, UUID organizationId, String email, String displayName, Role role,
            String locale) {
        return new UserAccount(id, organizationId, email, displayName, role, locale, Status.INVITED);
    }

    /** Just-in-time account for users who arrive through SSO without an invitation. */
    static UserAccount provisioned(UUID id, String email, String displayName, Role role) {
        return new UserAccount(id, null, email, displayName, role, "id", Status.ACTIVE);
    }

    void recordLogin(Instant at) {
        lastLoginAt = at;
        if (status == Status.INVITED) {
            status = Status.ACTIVE;
        }
    }

    void changeRole(Role role) {
        this.globalRole = role;
        this.mfaEnabled = role.requiresMfa();
    }

    void setEnabled(boolean enabled) {
        this.status = enabled ? (lastLoginAt == null ? Status.INVITED : Status.ACTIVE) : Status.DISABLED;
    }

    void changeLocale(String locale) {
        this.locale = locale;
    }

    UUID getId() {
        return id;
    }

    UUID getOrganizationId() {
        return organizationId;
    }

    String getEmail() {
        return email;
    }

    String getDisplayName() {
        return displayName;
    }

    Role getGlobalRole() {
        return globalRole;
    }

    String getLocale() {
        return locale;
    }

    Status getStatus() {
        return status;
    }

    boolean isMfaEnabled() {
        return mfaEnabled;
    }

    Instant getLastLoginAt() {
        return lastLoginAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
