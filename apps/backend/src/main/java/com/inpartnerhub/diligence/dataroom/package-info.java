/**
 * Data room (PRD DOC-01…DOC-09): folder tree per project with per-folder access grants, documents and versions in
 * S3-compatible storage. Uploads start in a logical quarantine (scan status PENDING) and are only served once the
 * ingestion pipeline reports them CLEAN. Visibility is enforced by row-level security (V7): the target side only
 * sees folders granted to it. Preview, watermark and download control follow in sprint 7–8.
 */
@ApplicationModule(displayName = "Data room", allowedDependencies = { "shared", "security", "project" })
package com.inpartnerhub.diligence.dataroom;

import org.springframework.modulith.ApplicationModule;
