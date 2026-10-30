package com.example.platform.document.internal;

import java.io.InputStream;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import com.example.platform.document.DocumentStorage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Credentials resolve through the AWS default provider chain (env vars locally, workload identity / Vault-injected
 * env in Kubernetes) — never from application config.
 */
@Component
class S3DocumentStorage implements DocumentStorage {

    private final S3Client s3;
    private final String bucket;

    S3DocumentStorage(S3Client s3, ObjectStorageProperties properties) {
        this.s3 = s3;
        this.bucket = properties.bucket();
    }

    @Override
    public void put(String objectKey, InputStream content, long sizeBytes, String contentType) {
        s3.putObject(r -> r.bucket(bucket).key(objectKey).contentType(contentType).contentLength(sizeBytes),
                RequestBody.fromInputStream(content, sizeBytes));
    }

    @Override
    public InputStream get(String objectKey) {
        return s3.getObject(r -> r.bucket(bucket).key(objectKey));
    }

    @Configuration(proxyBeanMethods = false)
    static class S3ClientConfiguration {

        @Bean(destroyMethod = "close")
        S3Client s3Client(ObjectStorageProperties properties) {
            var builder = S3Client.builder()
                    .region(Region.of(properties.region()))
                    .forcePathStyle(properties.pathStyle());
            if (properties.endpoint() != null) {
                builder.endpointOverride(properties.endpoint());
            }
            return builder.build();
        }
    }
}
