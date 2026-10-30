package com.inpartnerhub.diligence.request.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A comment on an item (PRD REQ-03). Internal notes are hidden from the target by row-level security. */
@Entity
@Table(schema = "req", name = "request_item_comment")
class RequestComment {

    static final int MAX_LENGTH = 4000;

    @Id
    private UUID id;

    @Column(name = "request_item_id", nullable = false, updatable = false)
    private UUID requestItemId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(nullable = false, updatable = false)
    private boolean internal;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RequestComment() {
    }

    RequestComment(RequestItem item, UUID authorId, String body, boolean internal) {
        this.id = UUID.randomUUID();
        this.requestItemId = item.getId();
        this.projectId = item.getProjectId();
        this.authorId = authorId;
        this.body = body;
        this.internal = internal;
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

    boolean isInternal() {
        return internal;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
