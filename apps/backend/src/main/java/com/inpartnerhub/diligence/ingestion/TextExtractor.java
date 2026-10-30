package com.inpartnerhub.diligence.ingestion;

import java.io.InputStream;

/** Port for text extraction (Apache Tika Server; Tesseract OCR is applied by Tika for image-based content). */
public interface TextExtractor {

    String extract(InputStream content, String contentType);
}
