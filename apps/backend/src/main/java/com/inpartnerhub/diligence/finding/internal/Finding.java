package com.inpartnerhub.diligence.finding.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.inpartnerhub.diligence.finding.FindingEvents.FindingRef;
import com.inpartnerhub.diligence.finding.RiskLevel;
import com.inpartnerhub.diligence.finding.RiskRating;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A due diligence finding (PRD FND-01) with its risk rating (FND-03) and workflow state (FND-04). */
@Entity
@Table(schema = "fnd", name = "finding")
class Finding {

    static final int MAX_TITLE = 300;
    static final int MAX_CATEGORY = 200;
    static final int MAX_TEXT = 10_000;

    /** Content of a finding, as entered by the team. */
    record Content(UUID workstreamId, String title, String category, String description, String impactDescription,
            BigDecimal monetaryImpact, String currency, String recommendation) {
    }

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "workstream_id", nullable = false)
    private UUID workstreamId;

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String description;

    @Column(name = "impact_description")
    private String impactDescription;

    @Column(name = "monetary_impact", precision = 19, scale = 2)
    private BigDecimal monetaryImpact;

    private String currency;

    private String recommendation;

    private Short impact;

    private Short likelihood;

    @Column(name = "risk_score")
    private Short riskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level")
    private RiskLevel riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FindingStatus status;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "released_by")
    private UUID releasedBy;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "archive_reason")
    private String archiveReason;

    @Version
    private long version;

    protected Finding() {
    }

    Finding(UUID projectId, String number, Content content, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.projectId = projectId;
        this.number = number;
        this.status = FindingStatus.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        update(content);
    }

    void update(Content content) {
        this.workstreamId = content.workstreamId();
        this.title = content.title();
        this.category = content.category();
        this.description = content.description();
        this.impactDescription = content.impactDescription();
        this.monetaryImpact = content.monetaryImpact();
        this.currency = content.monetaryImpact() == null ? null : content.currency();
        this.recommendation = content.recommendation();
        touch();
    }

    void rate(RiskRating rating) {
        if (rating == null) {
            impact = null;
            likelihood = null;
            riskScore = null;
            riskLevel = null;
        } else {
            impact = (short) rating.impact();
            likelihood = (short) rating.likelihood();
            riskScore = (short) rating.score();
            riskLevel = rating.level();
        }
        touch();
    }

    void moveTo(FindingStatus next, UUID userId, Instant at, String reason) {
        switch (next) {
            case IN_REVIEW -> {
                submittedBy = userId;
                submittedAt = at;
            }
            case APPROVED -> {
                approvedBy = userId;
                approvedAt = at;
            }
            case RELEASED -> {
                releasedBy = userId;
                releasedAt = at;
            }
            case ARCHIVED -> archiveReason = reason;
            case DRAFT -> {
                approvedBy = null;
                approvedAt = null;
            }
        }
        status = next;
        updatedAt = at;
    }

    void touch() {
        updatedAt = Instant.now();
    }

    boolean isRated() {
        return riskLevel != null;
    }

    FindingRef ref() {
        return new FindingRef(id, number, title, workstreamId, createdBy);
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    UUID getWorkstreamId() {
        return workstreamId;
    }

    String getNumber() {
        return number;
    }

    String getTitle() {
        return title;
    }

    String getCategory() {
        return category;
    }

    String getDescription() {
        return description;
    }

    String getImpactDescription() {
        return impactDescription;
    }

    BigDecimal getMonetaryImpact() {
        return monetaryImpact;
    }

    String getCurrency() {
        return currency;
    }

    String getRecommendation() {
        return recommendation;
    }

    Integer getImpact() {
        return impact == null ? null : impact.intValue();
    }

    Integer getLikelihood() {
        return likelihood == null ? null : likelihood.intValue();
    }

    Integer getRiskScore() {
        return riskScore == null ? null : riskScore.intValue();
    }

    RiskLevel getRiskLevel() {
        return riskLevel;
    }

    FindingStatus getStatus() {
        return status;
    }

    UUID getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    UUID getSubmittedBy() {
        return submittedBy;
    }

    Instant getSubmittedAt() {
        return submittedAt;
    }

    UUID getApprovedBy() {
        return approvedBy;
    }

    Instant getApprovedAt() {
        return approvedAt;
    }

    UUID getReleasedBy() {
        return releasedBy;
    }

    Instant getReleasedAt() {
        return releasedAt;
    }

    String getArchiveReason() {
        return archiveReason;
    }
}
