package com.inpartnerhub.diligence.ingestion;

import java.io.InputStream;
import java.time.Instant;

/** Port: stamps a dynamic watermark on a PDF rendition at view or download time (PRD DOC-04). */
public interface Watermarker {

    InputStream apply(InputStream pdf, WatermarkSpec spec);

    /** Identifies the viewer on every page: name, timestamp and IP address. */
    record WatermarkSpec(String viewerName, String viewerEmail, String ipAddress, Instant viewedAt) {
    }
}
