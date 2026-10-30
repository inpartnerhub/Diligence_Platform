/**
 * Asynchronous document pipeline (PRD Bab 8.3, DOC-02/03/04/06): quarantine → malware scan (ClamAV) → type
 * validation and hash → text extraction / OCR (Apache Tika + Tesseract) → PDF preview rendition (Gotenberg) →
 * search indexing; watermarking happens at view/download time. Driven by RabbitMQ.
 */
@ApplicationModule(displayName = "Ingestion", allowedDependencies = { "shared", "dataroom", "search", "security :: abac" })
package com.inpartnerhub.diligence.ingestion;

import org.springframework.modulith.ApplicationModule;
