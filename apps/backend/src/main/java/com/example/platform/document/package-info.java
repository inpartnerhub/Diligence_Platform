/**
 * Document lifecycle: metadata (PostgreSQL) and binary content (S3-compatible object storage).
 * Publishes {@link com.example.platform.document.DocumentUploaded} for downstream processing.
 */
@ApplicationModule(displayName = "Document")
package com.example.platform.document;

import org.springframework.modulith.ApplicationModule;
