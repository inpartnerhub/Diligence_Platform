package com.inpartnerhub.diligence.project.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.inpartnerhub.diligence.project.ProjectStage;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(schema = "prj", name = "project")
class Project {

    enum Status { ACTIVE, ARCHIVED, PURGED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "client_organization_id", nullable = false)
    private UUID clientOrganizationId;

    @Column(name = "target_organization_id", nullable = false)
    private UUID targetOrganizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectStage stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "ai_features_enabled", nullable = false)
    private boolean aiFeaturesEnabled;

    @Column(name = "template_id")
    private UUID templateId;

    @Column(name = "retention_period_days", nullable = false)
    private int retentionPeriodDays = 365;

    /** PRD Bab 10.1: network locations external users may reach this project from, one range per line. */
    @Column(name = "allowed_ip_ranges", nullable = false)
    private String allowedIpRanges = "";

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "purged_at")
    private Instant purgedAt;

    @Version
    private long version;

    protected Project() {
    }

    Project(String code, String name, UUID clientOrganizationId, UUID targetOrganizationId, LocalDate startDate,
            LocalDate endDate, UUID templateId, UUID createdBy) {
        this(code, name, clientOrganizationId, targetOrganizationId, startDate, endDate, templateId, createdBy, 365);
    }

    Project(String code, String name, UUID clientOrganizationId, UUID targetOrganizationId, LocalDate startDate,
            LocalDate endDate, UUID templateId, UUID createdBy, int retentionPeriodDays) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.name = name;
        this.clientOrganizationId = clientOrganizationId;
        this.targetOrganizationId = targetOrganizationId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.templateId = templateId;
        this.createdBy = createdBy;
        this.retentionPeriodDays = retentionPeriodDays > 0 ? retentionPeriodDays : 365;
        this.stage = ProjectStage.INITIATION;
        this.status = Status.ACTIVE;
        this.createdAt = Instant.now();
    }

    void update(String name, LocalDate startDate, LocalDate endDate, ProjectStage stage) {
        if (name != null && !name.isBlank()) {
            this.name = name.strip();
        }
        if (startDate != null) {
            this.startDate = startDate;
        }
        if (endDate != null) {
            this.endDate = endDate;
        }
        if (stage != null) {
            this.stage = stage;
        }
        validateDates(this.startDate, this.endDate);
    }

    void archive(Instant at) {
        status = Status.ARCHIVED;
        archivedAt = at;
        stage = ProjectStage.CLOSED;
    }

    void reactivate() {
        status = Status.ACTIVE;
        archivedAt = null;
    }

    void markPurged(Instant at) {
        status = Status.PURGED;
        purgedAt = at;
    }

    boolean isArchived() {
        return status == Status.ARCHIVED;
    }

    boolean isPurged() {
        return status == Status.PURGED;
    }

    void updateRetentionPeriod(int days) {
        if (days <= 0 || days > 3650) {
            throw new InvalidRequestException("Retention period must be between 1 and 3650 days");
        }
        this.retentionPeriodDays = days;
    }

    static final int MAX_IP_RANGES = 50;

    /** Replaces the IP allow-list; an empty list lifts the restriction. Every entry must be a valid range. */
    void updateAllowedIpRanges(List<String> ranges) {
        var cleaned = ranges == null ? List.<String>of()
                : ranges.stream().map(String::strip).filter(r -> !r.isEmpty()).distinct().toList();
        if (cleaned.size() > MAX_IP_RANGES) {
            throw new InvalidRequestException("At most " + MAX_IP_RANGES + " IP ranges per project");
        }
        cleaned.forEach(IpRange::parse);
        this.allowedIpRanges = String.join("\n", cleaned);
    }

    List<String> getAllowedIpRanges() {
        return allowedIpRanges == null || allowedIpRanges.isBlank() ? List.of()
                : List.of(allowedIpRanges.split("\n"));
    }

    /** Whether an external user at this address may access the project. */
    boolean allowsAddress(String ip) {
        var ranges = getAllowedIpRanges();
        return ranges.isEmpty() || ranges.stream().map(IpRange::parse).anyMatch(r -> r.contains(ip));
    }

    static void validateDates(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new InvalidRequestException("End date must not be before the start date");
        }
    }

    UUID getId() {
        return id;
    }

    String getCode() {
        return code;
    }

    String getName() {
        return name;
    }

    UUID getClientOrganizationId() {
        return clientOrganizationId;
    }

    UUID getTargetOrganizationId() {
        return targetOrganizationId;
    }

    ProjectStage getStage() {
        return stage;
    }

    Status getStatus() {
        return status;
    }

    LocalDate getStartDate() {
        return startDate;
    }

    LocalDate getEndDate() {
        return endDate;
    }

    UUID getTemplateId() {
        return templateId;
    }

    UUID getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getArchivedAt() {
        return archivedAt;
    }

    int getRetentionPeriodDays() {
        return retentionPeriodDays;
    }

    Instant getPurgedAt() {
        return purgedAt;
    }

    Instant getRetentionDeadline() {
        return archivedAt != null ? archivedAt.plus(java.time.Duration.ofDays(retentionPeriodDays)) : null;
    }
}
