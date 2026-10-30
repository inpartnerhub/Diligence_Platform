/**
 * Asynchronous document processing pipeline: malware scan (ClamAV) → text extraction (Apache Tika) → OCR fallback
 * (Tesseract, via Tika) → hand-off to search indexing. Driven by RabbitMQ.
 */
@ApplicationModule(displayName = "Ingestion", allowedDependencies = { "shared", "document", "search", "security :: abac" })
package com.example.platform.ingestion;

import org.springframework.modulith.ApplicationModule;
