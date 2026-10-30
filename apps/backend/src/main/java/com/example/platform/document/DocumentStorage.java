package com.example.platform.document;

import java.io.InputStream;

/** Port for binary document content. Implemented against S3-compatible storage. */
public interface DocumentStorage {

    void put(String objectKey, InputStream content, long sizeBytes, String contentType);

    InputStream get(String objectKey);
}
