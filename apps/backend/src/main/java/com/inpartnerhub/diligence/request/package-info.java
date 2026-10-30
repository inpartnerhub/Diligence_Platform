/**
 * Request list (PRD REQ-01…REQ-06): items per workstream created from the project template, by hand or from Excel;
 * staged release in waves; the six statuses with an enforced workflow and full history; documents and comments
 * delivered by the target; daily reminders; Excel and PDF export. The target side only sees released items and
 * never internal notes (row-level security, V9). Implements
 * {@link com.inpartnerhub.diligence.project.RequestStatistics} for project dashboards.
 */
@ApplicationModule(displayName = "Request list", allowedDependencies = { "shared", "security", "admin", "project",
        "dataroom", "notification", "ingestion" })
package com.inpartnerhub.diligence.request;

import org.springframework.modulith.ApplicationModule;
