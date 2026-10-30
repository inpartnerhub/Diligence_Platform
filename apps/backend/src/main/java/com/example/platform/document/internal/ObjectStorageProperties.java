package com.example.platform.document.internal;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param endpoint       S3 endpoint override (MinIO/Ceph); leave empty to use AWS regional endpoints
 * @param pathStyle      path-style addressing, required by most S3-compatible stores
 */
@ConfigurationProperties("platform.object-storage")
record ObjectStorageProperties(URI endpoint, String region, String bucket, boolean pathStyle) {
}
