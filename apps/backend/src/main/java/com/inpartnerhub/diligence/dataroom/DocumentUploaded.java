package com.inpartnerhub.diligence.dataroom;

import java.util.UUID;

import org.springframework.modulith.events.Externalized;

import com.inpartnerhub.diligence.shared.messaging.MessagingTopology;

/**
 * Integration event: a document version's content is stored (status PENDING) and awaits the ingestion pipeline.
 * Published through the transactional outbox to RabbitMQ.
 */
@Externalized(MessagingTopology.DOCUMENT_EVENTS_EXCHANGE + "::document.uploaded")
public record DocumentUploaded(UUID projectId, UUID documentId, UUID versionId, String objectKey, String fileName,
        String declaredContentType, long sizeBytes) {
}
