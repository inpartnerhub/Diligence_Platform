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
    REQUEST_OVERDUE_DIGEST,
    /** A finding awaits review (FND-04). Params: projectCode, findingNumber, title. */
    FINDING_SUBMITTED,
    /** A reviewer returned the author's finding to draft (FND-04). Params: projectCode, findingNumber, title, reason. */
    FINDING_RETURNED,
    /** A finding was approved and awaits release by a partner (FND-04). Params: projectCode, findingNumber, title. */
    FINDING_APPROVED,
    /** A finding was released to the client (FND-04). Params: projectCode, findingNumber, title. */
    FINDING_RELEASED,
    /** A finding was rated HIGH or CRITICAL (Appendix A escalation). Params: projectCode, findingNumber, title, level. */
    FINDING_ESCALATED,
    /** A question awaits approval before it goes to the target (QNA-02). Params: projectCode, questionNumber, excerpt. */
    QUESTION_PENDING_APPROVAL,
    /** A reviewer returned a question to its author (QNA-02). Params: projectCode, questionNumber, excerpt, feedback. */
    QUESTION_RETURNED,
    /** A question (or a follow-up) was sent to the target. Params: projectCode, questionNumber, excerpt, feedback. */
    QUESTION_SENT,
    /** The target answered a question. Params: projectCode, questionNumber, excerpt. */
    QUESTION_ANSWERED,
    /** The client's question was closed; its answer is now visible. Params: projectCode, questionNumber, excerpt. */
    QUESTION_CLOSED,
    /** A report was issued to the client (RPT-01). Params: projectCode, title, version. */
    REPORT_ISSUED
}
