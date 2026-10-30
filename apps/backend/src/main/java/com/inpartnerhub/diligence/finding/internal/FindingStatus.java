package com.inpartnerhub.diligence.finding.internal;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Validation workflow (PRD FND-04). Content is only edited in {@code DRAFT}; reviewers return or approve, a partner
 * releases to the client. Released findings are final: they can only be archived (with a reason).
 */
enum FindingStatus {

    DRAFT, IN_REVIEW, APPROVED, RELEASED, ARCHIVED;

    private static final Map<FindingStatus, Set<FindingStatus>> NEXT = Map.of(
            DRAFT, EnumSet.of(IN_REVIEW, ARCHIVED),
            IN_REVIEW, EnumSet.of(DRAFT, APPROVED, ARCHIVED),
            APPROVED, EnumSet.of(DRAFT, RELEASED, ARCHIVED),
            RELEASED, EnumSet.of(ARCHIVED),
            ARCHIVED, EnumSet.noneOf(FindingStatus.class));

    boolean mayMoveTo(FindingStatus next) {
        return NEXT.get(this).contains(next);
    }

    /** Moves that need a reason the author sees: returning, reopening and archiving. */
    boolean needsReason(FindingStatus next) {
        return next == ARCHIVED || (next == DRAFT && this != DRAFT);
    }

    boolean editable() {
        return this == DRAFT;
    }
}
