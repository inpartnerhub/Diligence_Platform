package com.inpartnerhub.diligence.shared.messaging;

/**
 * RabbitMQ naming contract. Exchanges are owned by the publishing module; queues by the consuming module.
 * Naming: {@code diligence.<module>.<purpose>}; dead-letter queues add a {@code .dlq} suffix.
 */
public final class MessagingTopology {

    public static final String DOCUMENT_EVENTS_EXCHANGE = "diligence.dataroom.events";
    public static final String INGESTION_QUEUE = "diligence.ingestion.document-uploaded";
    public static final String DEAD_LETTER_EXCHANGE = "diligence.dlx";

    private MessagingTopology() {
    }
}
