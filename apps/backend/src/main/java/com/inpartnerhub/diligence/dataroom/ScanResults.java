package com.inpartnerhub.diligence.dataroom;

import java.util.UUID;

/** Port through which the ingestion pipeline reports the outcome for a document version (PRD Bab 8.3). */
public interface ScanResults {

    void record(UUID versionId, Outcome outcome, String detectedContentType, String detail);

    enum Outcome {
        /** No malware and an allowed file type: the version may be served. */
        CLEAN,
        /** Malware found: the version stays quarantined and is never served. */
        INFECTED,
        /** The detected file type is not allowed in the data room. */
        REJECTED,
        /** The pipeline could not complete (scanner unavailable, size limit, …). */
        FAILED
    }
}
