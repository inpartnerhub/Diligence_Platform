package com.inpartnerhub.diligence.finding.internal;

import java.time.Instant;
import java.util.UUID;

import com.inpartnerhub.diligence.project.WorkstreamType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Firm-wide reusable standard finding template for cross-deal diligence consistency (PRD FND-06).
 */
@Entity
@Table(schema = "fnd", name = "finding_library_template")
class FindingLibraryTemplate {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "workstream_type", nullable = false, length = 32)
    private WorkstreamType workstreamType;

    @Column(nullable = false, length = 200)
    private String category;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(name = "standard_description", nullable = false)
    private String standardDescription;

    @Column(name = "default_recommendation")
    private String defaultRecommendation;

    @Column(name = "default_impact", nullable = false)
    private int defaultImpact;

    @Column(name = "default_likelihood", nullable = false)
    private int defaultLikelihood;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FindingLibraryTemplate() {
    }

    FindingLibraryTemplate(UUID id, WorkstreamType workstreamType, String category, String title,
            String standardDescription, String defaultRecommendation, int defaultImpact, int defaultLikelihood,
            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.workstreamType = workstreamType;
        this.category = category;
        this.title = title;
        this.standardDescription = standardDescription;
        this.defaultRecommendation = defaultRecommendation;
        this.defaultImpact = defaultImpact;
        this.defaultLikelihood = defaultLikelihood;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    void update(WorkstreamType workstreamType, String category, String title, String standardDescription,
            String defaultRecommendation, int defaultImpact, int defaultLikelihood, Instant updatedAt) {
        this.workstreamType = workstreamType;
        this.category = category;
        this.title = title;
        this.standardDescription = standardDescription;
        this.defaultRecommendation = defaultRecommendation;
        this.defaultImpact = defaultImpact;
        this.defaultLikelihood = defaultLikelihood;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public WorkstreamType getWorkstreamType() {
        return workstreamType;
    }

    public String getCategory() {
        return category;
    }

    public String getTitle() {
        return title;
    }

    public String getStandardDescription() {
        return standardDescription;
    }

    public String getDefaultRecommendation() {
        return defaultRecommendation;
    }

    public int getDefaultImpact() {
        return defaultImpact;
    }

    public int getDefaultLikelihood() {
        return defaultLikelihood;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
