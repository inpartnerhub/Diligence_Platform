package com.inpartnerhub.diligence.qna.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface QuestionRepository extends JpaRepository<Question, UUID>, JpaSpecificationExecutor<Question> {

    List<Question> findByProjectIdOrderByNumber(UUID projectId);

    /** Next free number of the project, counting questions the caller cannot see (V11). */
    @Query(value = "select qna.next_question_number(:projectId)", nativeQuery = true)
    String nextNumber(UUID projectId);

    /** Project purge (ADM-06, #114): child rows of this module go with it through ON DELETE CASCADE. */
    @Modifying
    @Query("delete from Question e where e.projectId = :projectId")
    int deleteAllForProject(@Param("projectId") UUID projectId);
}
