package com.inpartnerhub.diligence.project.internal;

import java.time.Instant;
import java.time.LocalDate;
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

    enum Status { ACTIVE, ARCHIVED }

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

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    private long version;

    protected Project() {
    }

    Project(String code, String name, UUID clientOrganizationId, UUID targetOrganizationId, LocalDate startDate,
            LocalDate endDate, UUID templateId, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.name = name;
        this.clientOrganizationId = clientOrganizationId;
        this.targetOrganizationId = targetOrganizationId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.templateId = templateId;
        this.createdBy = createdBy;
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

    boolean isArchived() {
        return status == Status.ARCHIVED;
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
}
