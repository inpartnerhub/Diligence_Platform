package com.inpartnerhub.diligence.finding.internal;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import com.inpartnerhub.diligence.project.ProjectDirectory.ProjectContext;
import com.inpartnerhub.diligence.project.ProjectRole;

final class FindingTestData {

    static final UUID PROJECT = UUID.randomUUID();
    static final UUID LEGAL = UUID.randomUUID();
    static final UUID TAX = UUID.randomUUID();

    private FindingTestData() {
    }

    static ProjectContext context(ProjectRole role) {
        return new ProjectContext(PROJECT, "DD-1", false, Set.of(role), Set.of());
    }

    static ProjectContext scoped(ProjectRole role, UUID workstream) {
        return new ProjectContext(PROJECT, "DD-1", false, Set.of(role), Set.of(workstream));
    }

    static Finding.Content content(UUID workstream) {
        return new Finding.Content(workstream, "Unregistered land title", "Assets",
                "Two plots are not registered in the company's name.", null, new BigDecimal("1500000000"), null,
                "Require registration as a condition precedent.");
    }

    static Finding finding(UUID author) {
        return new Finding(PROJECT, "F-LEG-001", content(LEGAL), author);
    }
}
