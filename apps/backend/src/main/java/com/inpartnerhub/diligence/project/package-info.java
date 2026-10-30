/**
 * Project management (PRD PRJ-01…PRJ-04): projects, workstreams, members with per-project (and optionally
 * per-workstream) roles, reusable templates and the project dashboard. Every project is a security boundary:
 * visibility is enforced by PostgreSQL row-level security on top of the checks in this module.
 */
@ApplicationModule(displayName = "Projects",
        allowedDependencies = { "shared", "security", "admin", "finding" })
package com.inpartnerhub.diligence.project;

import org.springframework.modulith.ApplicationModule;
