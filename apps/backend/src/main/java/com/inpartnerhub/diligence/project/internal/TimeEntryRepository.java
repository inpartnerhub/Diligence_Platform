package com.inpartnerhub.diligence.project.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
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

    /** Project purge (ADM-06, #123): one statement instead of loading and deleting each entry. */
    @Modifying
    @Query("delete from TimeEntry e where e.projectId = :projectId")
    void deleteByProjectId(UUID projectId);
}
