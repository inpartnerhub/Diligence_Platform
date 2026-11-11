package com.inpartnerhub.diligence.dataroom;

import java.io.InputStream;

/** Port for binary document content. Implemented against S3-compatible storage (encrypted at rest with KMS). */
public interface DocumentStorage {

    void put(String objectKey, InputStream content, long sizeBytes, String contentType);

    InputStream get(String objectKey);

    void delete(String objectKey);

    /**
     * Removes every object under a key prefix for good, including all earlier versions. Throws when anything could
     * not be deleted. Returns the number of object versions deleted.
     */
    long deletePrefix(String prefix);
}
