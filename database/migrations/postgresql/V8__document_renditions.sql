-- Sprint 7–8, preview, watermark, download control and search (PRD DOC-03…DOC-06).
--   * the ingestion pipeline pre-renders each clean version into page images (served watermarked per viewer) and,
--     for non-PDF types, a PDF rendition used for watermarked downloads
--   * extracted text (Tika, Tesseract OCR) is stored next to the renditions and indexed in OpenSearch

ALTER TABLE dr.document_version
    ADD COLUMN preview_status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (preview_status IN ('PENDING', 'READY', 'UNAVAILABLE', 'FAILED')),
    ADD COLUMN page_count     INT CHECK (page_count >= 0),
    ADD COLUMN text_extracted BOOLEAN NOT NULL DEFAULT false;

-- Versions processed before renditions existed have no preview; re-uploading them produces one.
UPDATE dr.document_version SET preview_status = 'UNAVAILABLE' WHERE scan_status <> 'PENDING';
