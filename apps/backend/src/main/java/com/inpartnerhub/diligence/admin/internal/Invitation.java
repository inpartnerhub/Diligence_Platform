package com.inpartnerhub.diligence.admin.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.inpartnerhub.diligence.security.Role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(schema = "adm", name = "invitation")
class Invitation {

    enum Status { PENDING, ACCEPTED, REVOKED }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String email;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    protected Invitation() {
    }

    Invitation(UUID userId, String email, UUID organizationId, Role role, UUID invitedBy, Duration lifespan) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.email = email;
        this.organizationId = organizationId;
        this.role = role;
        this.invitedBy = invitedBy;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
        this.expiresAt = createdAt.plus(lifespan);
    }

    void accept(Instant at) {
        if (status == Status.PENDING) {
            status = Status.ACCEPTED;
            acceptedAt = at;
        }
    }

    UUID getUserId() {
        return userId;
    }

    Status getStatus() {
        return status;
    }
}
