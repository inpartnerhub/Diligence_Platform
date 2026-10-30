package com.inpartnerhub.diligence.dataroom;

import java.util.UUID;

/** Port through which the ingestion pipeline reports the outcome for a document version (PRD Bab 8.3). */
public interface ScanResults {

    /** A version that may not be served: infected, rejected or not processable. */
    void record(UUID versionId, Outcome outcome, String detectedContentType, String detail);

    /** A clean version, with what the pipeline produced for preview (DOC-03) and search (DOC-06). */
    void recordClean(UUID versionId, String detectedContentType, Rendition rendition);

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

    enum PreviewStatus {
        /** Page images (and, for non-PDF types, a PDF rendition) are stored under {@link RenditionKeys}. */
        READY,
        /** The type has no visual rendition (e.g. e-mail); the document can only be downloaded. */
        UNAVAILABLE,
        /** Conversion failed (corrupt or password-protected file). */
        FAILED
    }

    /**
     * @param pageCount      number of stored page images ({@code 0} unless READY)
     * @param pdfRendition   whether a PDF rendition was stored (non-PDF originals with a preview)
     * @param textExtracted  whether extracted text was stored for indexing
     */
    record Rendition(PreviewStatus preview, int pageCount, boolean pdfRendition, boolean textExtracted) {

        public static final Rendition NONE = new Rendition(PreviewStatus.UNAVAILABLE, 0, false, false);
    }
}
