package com.inpartnerhub.diligence.ingestion;

import java.io.InputStream;
import java.nio.file.Path;

/** Port: converts Office and text documents to a PDF rendition for in-browser preview (PRD DOC-03). */
public interface PreviewRenderer {

    /** Whether the detected type can be converted. */
    boolean supports(String contentType);

    /** Writes the PDF rendition of the content to {@code target}. */
    void renderPdf(InputStream content, String fileName, String contentType, Path target);
}
