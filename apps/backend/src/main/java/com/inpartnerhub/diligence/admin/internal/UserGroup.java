package com.inpartnerhub.diligence.admin.internal;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Named set of users, used later for folder and workstream permissions (PRD DOC-01). */
@Entity
@Table(schema = "adm", name = "user_group")
class UserGroup {

    @Id
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(nullable = false)
    private String name;

    private String description;

    @ElementCollection
    @CollectionTable(schema = "adm", name = "group_member", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "user_id", nullable = false)
    private Set<UUID> memberIds = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected UserGroup() {
    }

    UserGroup(String name, String description, UUID organizationId) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.description = description;
        this.organizationId = organizationId;
        this.createdAt = Instant.now();
    }

    void replaceMembers(Set<UUID> userIds) {
        memberIds.clear();
        memberIds.addAll(userIds);
    }

    UUID getId() {
        return id;
    }

    UUID getOrganizationId() {
        return organizationId;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }

    Set<UUID> getMemberIds() {
        return Set.copyOf(memberIds);
    }
}
