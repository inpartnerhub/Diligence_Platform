package com.inpartnerhub.diligence.qna.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface QuestionStatusChangeRepository extends JpaRepository<QuestionStatusChange, UUID> {

    List<QuestionStatusChange> findByQuestionIdOrderByChangedAt(UUID questionId);
}
