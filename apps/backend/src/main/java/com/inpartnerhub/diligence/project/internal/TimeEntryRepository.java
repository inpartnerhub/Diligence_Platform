package com.inpartnerhub.diligence.project.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

interface TimeEntryRepository extends Repository<TimeEntry, UUID> {

    TimeEntry save(TimeEntry entry);

    Optional<TimeEntry> findById(UUID id);

    List<TimeEntry> findByProjectIdOrderByEntryDateDescCreatedAtDesc(UUID projectId);

    List<TimeEntry> findByProjectIdAndUserIdOrderByEntryDateDesc(UUID projectId, UUID userId);

    List<TimeEntry> findByProjectIdAndEntryDateBetweenOrderByEntryDateDesc(UUID projectId, LocalDate from,
            LocalDate to);

    List<TimeEntry> findByProjectIdAndUserIdAndEntryDateBetweenOrderByEntryDateDesc(UUID projectId, UUID userId,
            LocalDate from, LocalDate to);

    void delete(TimeEntry entry);

    void deleteByProjectId(UUID projectId);
}
