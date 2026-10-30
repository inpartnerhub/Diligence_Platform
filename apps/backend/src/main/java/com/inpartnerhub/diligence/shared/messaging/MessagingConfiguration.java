package com.inpartnerhub.diligence.shared.messaging;

import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.json.JsonMapper;

/**
 * JSON on the wire for RabbitMQ (outbox externalization and listeners). Only our own event types are trusted when
 * a message carries a type header.
 */
@Configuration(proxyBeanMethods = false)
class MessagingConfiguration {

    @Bean
    MessageConverter jsonMessageConverter(JsonMapper json) {
        return new JacksonJsonMessageConverter(json, "com.inpartnerhub.diligence");
    }
}
