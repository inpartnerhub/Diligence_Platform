/**
 * Immutable audit trail (PRD ADM-03, ADM-04): who did what, when and from where. Consumes every
 * {@link com.inpartnerhub.diligence.shared.audit.AuditableEvent} through the transactional outbox, so entries
 * survive broker or listener outages. The table rejects UPDATE/DELETE at the database level.
 */
@ApplicationModule(displayName = "Audit", allowedDependencies = "shared")
package com.inpartnerhub.diligence.audit;

import org.springframework.modulith.ApplicationModule;
