package com.inpartnerhub.diligence.dataroom.internal;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.dataroom.DocumentStorage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

/**
 * Credentials resolve through the AWS default provider chain (env vars locally, workload identity / Vault-injected
 * env in Kubernetes) — never from application config.
 */
@Component
class S3DocumentStorage implements DocumentStorage {

    /** S3 accepts at most 1,000 keys per DeleteObjects request. */
    static final int DELETE_BATCH = 1000;

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

    @Override
    public void delete(String objectKey) {
        s3.deleteObject(r -> r.bucket(bucket).key(objectKey));
    }

    /**
     * Deletes every version and delete marker under the prefix (project purge, ADM-06, #124). The bucket is versioned,
     * so a plain delete would only add delete markers and leave the documents recoverable; a purge must not. Fails
     * when S3 reports an object it could not delete, so the purge is retried instead of being recorded as complete.
     *
     * @return the number of object versions deleted (delete markers not counted)
     */
    @Override
    public long deletePrefix(String prefix) {
        long count = 0;
        var batch = new ArrayList<ObjectIdentifier>(DELETE_BATCH);
        for (var page : s3.listObjectVersionsPaginator(r -> r.bucket(bucket).prefix(prefix))) {
            for (var version : page.versions()) {
                batch.add(ObjectIdentifier.builder().key(version.key()).versionId(version.versionId()).build());
                count++;
                flushIfFull(batch);
            }
            for (var marker : page.deleteMarkers()) {
                batch.add(ObjectIdentifier.builder().key(marker.key()).versionId(marker.versionId()).build());
                flushIfFull(batch);
            }
        }
        flush(batch);
        return count;
    }

    private void flushIfFull(List<ObjectIdentifier> batch) {
        if (batch.size() >= DELETE_BATCH) {
            flush(batch);
        }
    }

    private void flush(List<ObjectIdentifier> batch) {
        if (batch.isEmpty()) {
            return;
        }
        var objects = List.copyOf(batch);
        batch.clear();
        var response = s3.deleteObjects(r -> r.bucket(bucket).delete(d -> d.objects(objects).quiet(true)));
        if (response.hasErrors() && !response.errors().isEmpty()) {
            var first = response.errors().getFirst();
            throw new IllegalStateException("S3 could not delete %d of %d objects (first: %s %s)".formatted(
                    response.errors().size(), objects.size(), first.code(), first.key()));
        }
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
