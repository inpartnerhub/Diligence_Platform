package com.example.platform.shared.messaging;

/**
 * RabbitMQ naming contract. Exchanges are owned by the publishing module; queues by the consuming module.
 * Naming: {@code platform.<module>.<purpose>}; dead-letter queues add a {@code .dlq} suffix.
 */
public final class MessagingTopology {

    public static final String DOCUMENT_EVENTS_EXCHANGE = "platform.document.events";
    public static final String INGESTION_QUEUE = "platform.ingestion.document-uploaded";
    public static final String DEAD_LETTER_EXCHANGE = "platform.dlx";

    private MessagingTopology() {
    }
}
