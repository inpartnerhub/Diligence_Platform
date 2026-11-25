package com.inpartnerhub.diligence.dataroom;

import java.util.UUID;

/**
 * Integration event: a document version's content is stored (status PENDING) and awaits the ingestion pipeline.
 * Enqueued in PostgreSQL ({@code dr.ingestion_job}) inside the upload transaction.
 */
public record DocumentUploaded(UUID projectId, UUID documentId, UUID versionId, String objectKey, String fileName,
        String declaredContentType, long sizeBytes) {
}
