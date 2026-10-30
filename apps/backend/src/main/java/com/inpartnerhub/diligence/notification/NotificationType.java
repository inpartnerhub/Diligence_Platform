package com.inpartnerhub.diligence.notification;

/**
 * Kinds of notifications (PRD ADM-05). Users choose e-mail and in-app delivery per type; the frontend renders in-app
 * texts from the type and its parameters.
 */
public enum NotificationType {

    /** Request-list items were released to the target (REQ-01/06). Params: projectCode, count, wave. */
    REQUEST_RELEASED,
    /** The target delivered documents for an item (REQ-03). Params: projectCode, itemNumber, description, status. */
    REQUEST_SUBMITTED,
    /** A consultant closed or rejected an item (REQ-02). Params: projectCode, itemNumber, description, status, reason. */
    REQUEST_REVIEWED,
    /** New comment on an item (REQ-03). Params: projectCode, itemNumber, description. */
    REQUEST_COMMENTED,
    /** Open items are due soon or overdue (REQ-04). Params: projectCode, dueSoon, overdue. */
    REQUEST_DUE_REMINDER,
    /** Daily digest of overdue items for the project's managers (REQ-04). Params: projectCode, overdue. */
    REQUEST_OVERDUE_DIGEST
}
