package com.inpartnerhub.diligence.qna.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.project.ProjectEvents.ProjectDataPurgeRequested;

/**
 * Project purge (PRD ADM-06, UU PDP, #114): deletes this module's questions (with answers, linked documents and status history) when an archived project
 * passes its retention period. Runs without a signed-in user, i.e. as the RLS system role.
 */
@Component
class QnaRetentionListener {

    private static final Logger log = LoggerFactory.getLogger(QnaRetentionListener.class);

    private final QuestionRepository questions;

    QnaRetentionListener(QuestionRepository questions) {
        this.questions = questions;
    }

    @ApplicationModuleListener
    void on(ProjectDataPurgeRequested event) {
        int deleted = questions.deleteAllForProject(event.projectId());
        log.info("Purged {} questions of project {} ({})", deleted, event.code(), event.projectId());
    }
}
