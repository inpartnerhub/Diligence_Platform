package com.inpartnerhub.diligence.dataroom;

import java.io.InputStream;

/** Port for binary document content. Implemented against S3-compatible storage (encrypted at rest with KMS). */
public interface DocumentStorage {

    void put(String objectKey, InputStream content, long sizeBytes, String contentType);

    InputStream get(String objectKey);

    void delete(String objectKey);
}
