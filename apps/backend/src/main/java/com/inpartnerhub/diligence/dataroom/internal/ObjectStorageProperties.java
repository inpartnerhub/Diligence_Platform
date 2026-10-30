package com.inpartnerhub.diligence.dataroom.internal;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param endpoint       S3 endpoint override (SeaweedFS, Ceph, …); leave empty to use AWS regional endpoints
 * @param pathStyle      path-style addressing, required by most S3-compatible stores
 */
@ConfigurationProperties("diligence.object-storage")
record ObjectStorageProperties(URI endpoint, String region, String bucket, boolean pathStyle) {
}
