package com.inpartnerhub.diligence.ingestion;

import java.io.InputStream;

/** Port: detects the real file type from content (magic bytes), not from the client-supplied header. */
public interface ContentTypeDetector {

    String detect(InputStream content, String fileName);
}
