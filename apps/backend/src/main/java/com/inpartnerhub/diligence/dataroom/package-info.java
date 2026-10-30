/**
 * Data room (PRD DOC-01…DOC-09): folder tree per project with per-folder access grants, documents and versions in
 * S3-compatible storage. Uploads start in a logical quarantine (scan status PENDING) and are only served once the
 * ingestion pipeline reports them CLEAN. Visibility is enforced by row-level security (V7): the target side only
 * sees folders granted to it. Clean versions are previewed as pre-rendered page images watermarked per viewer
 * (DOC-03/04), downloads follow the folder policy and are logged (DOC-05), and the current version of each document is
 * searchable within the folders a user may see (DOC-06).
 */
@ApplicationModule(displayName = "Data room", allowedDependencies = { "shared", "security", "project", "search" })
package com.inpartnerhub.diligence.dataroom;

import org.springframework.modulith.ApplicationModule;
