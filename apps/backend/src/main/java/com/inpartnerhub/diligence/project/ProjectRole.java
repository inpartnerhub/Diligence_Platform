package com.inpartnerhub.diligence.project;

import java.util.EnumSet;
import java.util.Set;

import com.inpartnerhub.diligence.security.Role;

/** Role of a user within one project (PRD Bab 3). Stored in {@code prj.project_member.project_role}. */
public enum ProjectRole {

    PARTNER, PROJECT_MANAGER, ANALYST, CLIENT, TARGET, OBSERVER;

    /** Roles allowed to change the project, its workstreams and its team. */
    public static final Set<ProjectRole> MANAGERS = EnumSet.of(PARTNER, PROJECT_MANAGER);

    /** Which project roles a user with the given global role may hold. External users keep their side. */
    public static Set<ProjectRole> allowedFor(Role globalRole) {
        return switch (globalRole) {
            case PARTNER, PROJECT_MANAGER, ANALYST -> EnumSet.of(PARTNER, PROJECT_MANAGER, ANALYST, OBSERVER);
            case CLIENT -> EnumSet.of(CLIENT);
            case TARGET -> EnumSet.of(TARGET);
            case OBSERVER -> EnumSet.of(OBSERVER);
            case SUPER_ADMIN -> EnumSet.noneOf(ProjectRole.class);
        };
    }

    public boolean isExternal() {
        return this == CLIENT || this == TARGET;
    }
}
