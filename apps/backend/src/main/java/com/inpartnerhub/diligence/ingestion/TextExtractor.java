package com.inpartnerhub.diligence.ingestion;

import java.io.InputStream;

/** Port for text extraction (Apache Tika Server; Tesseract OCR is applied by Tika for image-based content). */
public interface TextExtractor {

    /** Plain text of the content, at most {@code maxChars} characters; empty when there is none. */
    String extract(InputStream content, String contentType, int maxChars);
}
