package com.inpartnerhub.diligence.qna.internal;

/**
 * Question workflow (PRD QNA-02): drafted by the team (or asked by the client, which starts at approval), approved
 * and sent to the target by a partner or project manager, answered by the target, then closed by the team or sent
 * back with a follow-up. Only {@code SENT} and later are visible to the target.
 */
enum QuestionStatus {

    DRAFT, PENDING_APPROVAL, SENT, ANSWERED, CLOSED;

    /** Open for the target's answer. */
    boolean answerable() {
        return this == SENT || this == ANSWERED;
    }

    /** Still being prepared by the team (wording can change). */
    boolean editable() {
        return this == DRAFT || this == PENDING_APPROVAL;
    }
}
