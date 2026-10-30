package com.inpartnerhub.diligence.dataroom;

import java.util.UUID;

/**
 * Object keys of what the pipeline derives from a version. Kept beside, not below, the original's key: some
 * S3-compatible stores cannot hold both an object and a "directory" with the same name.
 */
public final class RenditionKeys {

    private RenditionKeys() {
    }

    public static String page(UUID projectId, UUID versionId, int pageNumber) {
        return prefix(projectId, versionId) + "pages/%04d.png".formatted(pageNumber);
    }

    public static String pdf(UUID projectId, UUID versionId) {
        return prefix(projectId, versionId) + "document.pdf";
    }

    public static String text(UUID projectId, UUID versionId) {
        return prefix(projectId, versionId) + "text.txt";
    }

    private static String prefix(UUID projectId, UUID versionId) {
        return "projects/%s/renditions/%s/".formatted(projectId, versionId);
    }
}
