package com.inpartnerhub.diligence.dataroom.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Error;

/** Project purge in a versioned bucket (#124): every version and delete marker goes, and errors are not ignored. */
class S3DocumentStorageTest {

    private static final String PREFIX = "projects/p1/";

    // The SDK's S3Client methods are interface defaults; the paginator is real, the calls below are stubbed.
    private final S3Client s3 = mock(S3Client.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    private final S3DocumentStorage storage = new S3DocumentStorage(s3, new ObjectStorageProperties(null, "us-east-1",
            "bucket", true));

    @Test
    void deletesEveryVersionAndDeleteMarkerUnderThePrefix() {
        doReturn(ListObjectVersionsResponse.builder().isTruncated(false)
                .versions(version("projects/p1/a", "v2"), version("projects/p1/a", "v1"), version("projects/p1/b", "v1"))
                .deleteMarkers(DeleteMarkerEntry.builder().key("projects/p1/c").versionId("m1").build())
                .build()).when(s3).listObjectVersions(any(ListObjectVersionsRequest.class));
        doReturn(DeleteObjectsResponse.builder().build()).when(s3).deleteObjects(any(DeleteObjectsRequest.class));

        assertThat(storage.deletePrefix(PREFIX)).isEqualTo(3);

        var request = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3).deleteObjects(request.capture());
        assertThat(request.getValue().delete().objects()).extracting(ObjectIdentifier::key, ObjectIdentifier::versionId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("projects/p1/a", "v2"),
                        org.assertj.core.groups.Tuple.tuple("projects/p1/a", "v1"),
                        org.assertj.core.groups.Tuple.tuple("projects/p1/b", "v1"),
                        org.assertj.core.groups.Tuple.tuple("projects/p1/c", "m1"));
        var listed = ArgumentCaptor.forClass(ListObjectVersionsRequest.class);
        verify(s3).listObjectVersions(listed.capture());
        assertThat(listed.getValue().prefix()).isEqualTo(PREFIX);
    }

    @Test
    void deletesInBatchesOfAtMostOneThousand() {
        var versions = new ArrayList<ObjectVersion>();
        IntStream.range(0, 2_500).forEach(i -> versions.add(version("projects/p1/" + i, "v1")));
        doReturn(ListObjectVersionsResponse.builder().isTruncated(false).versions(versions).build())
                .when(s3).listObjectVersions(any(ListObjectVersionsRequest.class));
        doReturn(DeleteObjectsResponse.builder().build()).when(s3).deleteObjects(any(DeleteObjectsRequest.class));

        assertThat(storage.deletePrefix(PREFIX)).isEqualTo(2_500);

        var requests = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3, times(3)).deleteObjects(requests.capture());
        assertThat(requests.getAllValues()).extracting(r -> r.delete().objects().size())
                .containsExactly(1_000, 1_000, 500);
    }

    @Test
    void failsWhenS3ReportsObjectsItCouldNotDelete() {
        doReturn(ListObjectVersionsResponse.builder().isTruncated(false)
                .versions(version("projects/p1/a", "v1")).build())
                .when(s3).listObjectVersions(any(ListObjectVersionsRequest.class));
        doReturn(DeleteObjectsResponse.builder()
                .errors(List.of(S3Error.builder().key("projects/p1/a").code("AccessDenied").build())).build())
                .when(s3).deleteObjects(any(DeleteObjectsRequest.class));

        assertThatThrownBy(() -> storage.deletePrefix(PREFIX)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AccessDenied");
    }

    @Test
    void anEmptyPrefixDeletesNothing() {
        doReturn(ListObjectVersionsResponse.builder().isTruncated(false).build())
                .when(s3).listObjectVersions(any(ListObjectVersionsRequest.class));

        assertThat(storage.deletePrefix(PREFIX)).isZero();

        verify(s3, times(0)).deleteObjects(any(DeleteObjectsRequest.class));
    }

    private static ObjectVersion version(String key, String versionId) {
        return ObjectVersion.builder().key(key).versionId(versionId).build();
    }
}
