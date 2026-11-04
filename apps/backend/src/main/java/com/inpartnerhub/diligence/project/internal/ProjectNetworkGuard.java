package com.inpartnerhub.diligence.project.internal;

import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.project.ProjectEvents.ProjectAccessBlocked;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Checks a project's IP allow-list (PRD Bab 10.1). A refusal is recorded in the audit log; the transaction commits
 * before the caller rejects the request, so the event is never rolled back with it. Refusals are also counted as
 * {@code diligence_project_access_blocked_total}, which drives the suspicious-access alert (PRD Bab 7).
 */
@Component
class ProjectNetworkGuard {

    private final ProjectRepository projects;
    private final ApplicationEventPublisher events;
    private final Counter blocked;

    ProjectNetworkGuard(ProjectRepository projects, ApplicationEventPublisher events, MeterRegistry meters) {
        this.projects = projects;
        this.events = events;
        this.blocked = Counter.builder("diligence.project.access.blocked")
                .description("External requests refused by a project's IP allow-list (PRD 10.1)")
                .register(meters);
    }

    /** Whether the caller may reach the project from {@code ip}; unknown or invisible projects are left to the API. */
    @Transactional
    boolean permits(UUID projectId, String ip) {
        var project = projects.findById(projectId).orElse(null);
        if (project == null || project.allowsAddress(ip)) {
            return true;
        }
        blocked.increment();
        events.publishEvent(new ProjectAccessBlocked(projectId, project.getCode(), RequestMetadata.capture()));
        return false;
    }
}
