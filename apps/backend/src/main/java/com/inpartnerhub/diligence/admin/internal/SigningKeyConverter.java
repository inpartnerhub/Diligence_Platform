package com.inpartnerhub.diligence.admin.internal;

import org.springframework.stereotype.Component;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores {@code adm.webhook_subscription.signing_key} encrypted; the entity holds the plain key in memory only. */
@Component
@Converter
class SigningKeyConverter implements AttributeConverter<String, String> {

    private final WebhookKeyCipher cipher;

    SigningKeyConverter(WebhookKeyCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String plain) {
        return cipher.encrypt(plain);
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        return cipher.decrypt(stored);
    }
}
