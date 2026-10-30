/**
 * Data room (PRD DOC-01…DOC-09): folders with per-folder permissions, documents and versions in S3-compatible
 * storage, watermarked preview and download control. Publishes
 * {@link com.inpartnerhub.diligence.dataroom.DocumentUploaded} for the ingestion pipeline. Arrives in sprint 5–8.
 */
@ApplicationModule(displayName = "Data room", allowedDependencies = "shared")
package com.inpartnerhub.diligence.dataroom;

import org.springframework.modulith.ApplicationModule;
