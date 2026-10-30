package com.inpartnerhub.diligence.ingestion;

import java.io.InputStream;

/** Port: converts Office and image documents to a PDF rendition for in-browser preview (PRD DOC-03). */
public interface PreviewRenderer {

    InputStream renderPdf(InputStream content, String fileName, String contentType);
}
