package com.inpartnerhub.diligence.project.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.inpartnerhub.diligence.project.WorkstreamType;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

final class TimeEntryDto {

    private TimeEntryDto() {
    }

    record CreateTimeEntryRequest(
            @NotNull WorkstreamType workstreamType,
            @NotNull LocalDate entryDate,
            @NotNull @DecimalMin("0.25") @DecimalMax("24.00") BigDecimal hours,
            @Size(max = 2000) String description) {
    }

    record UpdateTimeEntryRequest(
            @NotNull WorkstreamType workstreamType,
            @NotNull LocalDate entryDate,
            @NotNull @DecimalMin("0.25") @DecimalMax("24.00") BigDecimal hours,
            @Size(max = 2000) String description) {
    }

    record TimeEntryResponse(
            UUID id,
            UUID projectId,
            WorkstreamType workstreamType,
            UUID userId,
            String userName,
            LocalDate entryDate,
            BigDecimal hours,
            String description,
            Instant createdAt,
            Instant updatedAt) {
    }

    record TimeEntrySummaryResponse(
            BigDecimal totalHours,
            Map<WorkstreamType, BigDecimal> byWorkstream,
            List<ConsultantWorkloadResponse> byConsultant) {
    }

    record ConsultantWorkloadResponse(
            UUID userId,
            String userName,
            BigDecimal totalHours,
            int entryCount) {
    }
}
