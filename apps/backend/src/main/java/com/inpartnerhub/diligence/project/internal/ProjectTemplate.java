package com.inpartnerhub.diligence.project.internal;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.ColumnTransformer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Reusable project setup (PRD PRJ-03). The definition is stored as JSON, see {@link TemplateDefinition}. */
@Entity
@Table(schema = "prj", name = "project_template")
class ProjectTemplate {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String definition;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected ProjectTemplate() {
    }

    ProjectTemplate(String name, String description, String definition) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.description = description;
        this.definition = definition;
        this.createdAt = Instant.now();
    }

    void update(String name, String description, String definition) {
        this.name = name;
        this.description = description;
        this.definition = definition;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }

    String getDefinition() {
        return definition;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
