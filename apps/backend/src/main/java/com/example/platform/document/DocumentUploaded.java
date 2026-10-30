package com.example.platform.document;

import java.util.UUID;

import org.springframework.modulith.events.Externalized;

import com.example.platform.shared.messaging.MessagingTopology;

/** Integration event: a document's binary content has been stored and awaits ingestion. */
@Externalized(MessagingTopology.DOCUMENT_EVENTS_EXCHANGE + "::document.uploaded")
public record DocumentUploaded(UUID documentId, String objectKey, String contentType, long sizeBytes) {
}
