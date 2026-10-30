package com.inpartnerhub.diligence.request.internal;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The six item statuses (PRD REQ-02) and who may move an item between them. The internal team drives the workflow;
 * the target only reports delivery (partial or complete) on items released to it. {@code REJECTED} means the
 * delivered documents were not sufficient: the item is open again for the target.
 */
enum RequestStatus {

    NOT_REQUESTED, REQUESTED, PARTIAL, RECEIVED, REJECTED, CLOSED;

    /** Items waiting for the target (count for reminders, due-soon and overdue figures). */
    static final Set<RequestStatus> OPEN = EnumSet.of(REQUESTED, PARTIAL, REJECTED);

    private static final Map<RequestStatus, Set<RequestStatus>> INTERNAL = Map.of(
            NOT_REQUESTED, EnumSet.of(REQUESTED, CLOSED),
            REQUESTED, EnumSet.of(NOT_REQUESTED, PARTIAL, RECEIVED, CLOSED),
            PARTIAL, EnumSet.of(REQUESTED, RECEIVED, REJECTED, CLOSED),
            RECEIVED, EnumSet.of(PARTIAL, REJECTED, CLOSED),
            REJECTED, EnumSet.of(REQUESTED, PARTIAL, RECEIVED, CLOSED),
            CLOSED, EnumSet.of(REQUESTED, RECEIVED));

    private static final Set<RequestStatus> TARGET_RESULTS = EnumSet.of(PARTIAL, RECEIVED);

    boolean internalMayMoveTo(RequestStatus next) {
        return INTERNAL.get(this).contains(next);
    }

    boolean targetMayMoveTo(RequestStatus next) {
        return OPEN.contains(this) && TARGET_RESULTS.contains(next);
    }

    /** Whether the target may still add or remove documents (released and not closed). */
    boolean acceptsDeliveries() {
        return this != NOT_REQUESTED && this != CLOSED;
    }
}
