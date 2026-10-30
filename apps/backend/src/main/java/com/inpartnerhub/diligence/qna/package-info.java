/**
 * Q&A (PRD QNA-01…QNA-03): questions to the target linked to documents, request items or findings; approval by a
 * partner or project manager before anything reaches the target; answers with data-room documents; review and close
 * by the team; search and Excel/PDF export of the log. The target only sees questions sent to it, the client only its
 * own questions and their answers once the team has closed them (row-level security, V5 and V11).
 */
@ApplicationModule(displayName = "Q&A", allowedDependencies = { "shared", "security", "admin", "project", "dataroom",
        "request", "finding", "notification", "ingestion" })
package com.inpartnerhub.diligence.qna;

import org.springframework.modulith.ApplicationModule;
