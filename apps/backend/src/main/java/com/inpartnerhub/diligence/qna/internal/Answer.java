package com.inpartnerhub.diligence.qna.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The target's answer; the client reads it only after the team closed the question (row-level security, V11). */
@Entity
@Table(schema = "qna", name = "answer")
class Answer {

    static final int MAX_BODY = 10_000;

    @Id
    @Column(name = "question_id")
    private UUID questionId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(nullable = false)
    private String body;

    @Column(name = "answered_by", nullable = false)
    private UUID answeredBy;

    @Column(name = "answered_at", nullable = false)
    private Instant answeredAt;

    protected Answer() {
    }

    Answer(Question question, String body, UUID answeredBy) {
        this.questionId = question.getId();
        this.projectId = question.getProjectId();
        revise(body, answeredBy);
    }

    void revise(String body, UUID answeredBy) {
        this.body = body;
        this.answeredBy = answeredBy;
        this.answeredAt = Instant.now();
    }

    UUID getQuestionId() {
        return questionId;
    }

    String getBody() {
        return body;
    }

    UUID getAnsweredBy() {
        return answeredBy;
    }

    Instant getAnsweredAt() {
        return answeredAt;
    }
}
