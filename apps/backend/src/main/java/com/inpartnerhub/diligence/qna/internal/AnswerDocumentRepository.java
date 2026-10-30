package com.inpartnerhub.diligence.qna.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AnswerDocumentRepository extends JpaRepository<AnswerDocument, AnswerDocument.Key> {

    List<AnswerDocument> findByQuestionIdOrderByLinkedAt(UUID questionId);
}
