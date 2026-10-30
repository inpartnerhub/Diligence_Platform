package com.inpartnerhub.diligence.request.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.project.ProjectRole;

final class RequestTestData {

    static final UUID PROJECT = UUID.randomUUID();
    static final UUID LEGAL = UUID.randomUUID();
    static final UUID TAX = UUID.randomUUID();

    private RequestTestData() {
    }

    static ProjectContext context(ProjectRole role) {
        return new ProjectContext(PROJECT, "DD-1", false, Set.of(role), Set.of());
    }

    static ProjectContext scoped(ProjectRole role, UUID workstream) {
        return new ProjectContext(PROJECT, "DD-1", false, Set.of(role), Set.of(workstream));
    }

    static RequestItem item(String number, RequestStatus status) {
        var item = new RequestItem(PROJECT, LEGAL, number, "Corporate", "Deed of establishment",
                RequestPriority.MEDIUM, LocalDate.of(2026, 11, 10), 1);
        if (status != RequestStatus.NOT_REQUESTED) {
            item.moveTo(RequestStatus.REQUESTED, Instant.now());
            if (status != RequestStatus.REQUESTED) {
                item.moveTo(status, Instant.now());
            }
        }
        return item;
    }
}
