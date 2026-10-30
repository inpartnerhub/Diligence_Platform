package com.inpartnerhub.diligence.qna.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface QuestionRepository extends JpaRepository<Question, UUID> {

    List<Question> findByProjectIdOrderByNumber(UUID projectId);

    /** Next free number of the project, counting questions the caller cannot see (V11). */
    @Query(value = "select qna.next_question_number(:projectId)", nativeQuery = true)
    String nextNumber(UUID projectId);
}
