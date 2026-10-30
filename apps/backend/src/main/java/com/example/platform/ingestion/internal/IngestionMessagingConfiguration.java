package com.example.platform.ingestion.internal;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.platform.shared.messaging.MessagingTopology;

/** Declares the ingestion queue (quorum, with dead-lettering) bound to document events. */
@Configuration(proxyBeanMethods = false)
class IngestionMessagingConfiguration {

    private static final String DLQ = MessagingTopology.INGESTION_QUEUE + ".dlq";

    @Bean
    TopicExchange documentEventsExchange() {
        return new TopicExchange(MessagingTopology.DOCUMENT_EVENTS_EXCHANGE);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(MessagingTopology.DEAD_LETTER_EXCHANGE);
    }

    @Bean
    Queue ingestionQueue() {
        return QueueBuilder.durable(MessagingTopology.INGESTION_QUEUE)
                .quorum()
                .deadLetterExchange(MessagingTopology.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DLQ)
                .build();
    }

    @Bean
    Queue ingestionDeadLetterQueue() {
        return QueueBuilder.durable(DLQ).quorum().build();
    }

    @Bean
    Binding ingestionBinding() {
        return BindingBuilder.bind(ingestionQueue()).to(documentEventsExchange()).with("document.uploaded");
    }

    @Bean
    Binding ingestionDeadLetterBinding() {
        return BindingBuilder.bind(ingestionDeadLetterQueue()).to(deadLetterExchange()).with(DLQ);
    }
}
