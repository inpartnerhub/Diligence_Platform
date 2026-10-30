/**
 * Asynchronous document pipeline (PRD Bab 8.3, DOC-02/03/06): quarantine → malware scan (ClamAV) → type
 * validation → text extraction / OCR (Apache Tika + Tesseract) → PDF rendition (Gotenberg) → page images. Results
 * go back to the data room, which indexes the text and watermarks pages per viewer. Driven by RabbitMQ.
 */
@ApplicationModule(displayName = "Ingestion", allowedDependencies = { "shared", "dataroom" })
package com.inpartnerhub.diligence.ingestion;

import org.springframework.modulith.ApplicationModule;
