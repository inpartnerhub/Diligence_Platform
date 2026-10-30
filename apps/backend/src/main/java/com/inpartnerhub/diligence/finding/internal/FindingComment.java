package com.inpartnerhub.diligence.finding.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Internal review note on a finding; never visible to the client (row-level security, V10). */
@Entity
@Table(schema = "fnd", name = "finding_comment")
class FindingComment {

    static final int MAX_LENGTH = 4000;

    @Id
    private UUID id;

    @Column(name = "finding_id", nullable = false, updatable = false)
    private UUID findingId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FindingComment() {
    }

    FindingComment(Finding finding, UUID authorId, String body) {
        this.id = UUID.randomUUID();
        this.findingId = finding.getId();
        this.projectId = finding.getProjectId();
        this.authorId = authorId;
        this.body = body;
        this.createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    UUID getAuthorId() {
        return authorId;
    }

    String getBody() {
        return body;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
