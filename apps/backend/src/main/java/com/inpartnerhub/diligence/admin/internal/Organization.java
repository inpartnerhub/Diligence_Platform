package com.inpartnerhub.diligence.admin.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(schema = "adm", name = "organization")
class Organization {

    enum Type { CONSULTANT, CLIENT, TARGET }

    enum Status { ACTIVE, INACTIVE }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected Organization() {
    }

    Organization(String name, Type type) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.type = type;
        this.status = Status.ACTIVE;
        this.createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    Type getType() {
        return type;
    }

    Status getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void rename(String name) {
        this.name = name;
    }

    void changeStatus(Status status) {
        this.status = status;
    }
}
