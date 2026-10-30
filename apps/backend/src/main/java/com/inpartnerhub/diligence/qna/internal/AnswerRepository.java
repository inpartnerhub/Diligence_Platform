package com.inpartnerhub.diligence.qna.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AnswerRepository extends JpaRepository<Answer, UUID> {

    List<Answer> findByQuestionIdIn(Collection<UUID> questionIds);
}
