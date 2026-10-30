package com.inpartnerhub.diligence.dataroom.internal;

import java.util.Set;
import java.util.UUID;

import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.project.ProjectRole;

final class DataRoomTestData {

    static final UUID PROJECT = UUID.randomUUID();

    private DataRoomTestData() {
    }

    static ProjectContext context(ProjectRole role) {
        return new ProjectContext(PROJECT, "DD-1", false, Set.of(role), Set.of());
    }

    static ProjectContext scoped(ProjectRole role, UUID workstream) {
        return new ProjectContext(PROJECT, "DD-1", false, Set.of(role), Set.of(workstream));
    }

    static ProjectContext archived(ProjectRole role) {
        return new ProjectContext(PROJECT, "DD-1", true, Set.of(role), Set.of());
    }
}
