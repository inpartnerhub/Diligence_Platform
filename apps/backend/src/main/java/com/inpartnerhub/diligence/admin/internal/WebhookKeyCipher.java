package com.inpartnerhub.diligence.admin.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Encrypts webhook signing keys at rest with AES-256-GCM (issue #64). The key-encryption key comes from Vault through
 * the environment (ADR-0002: no Vault client in the application). Stored values look like
 * {@code enc:v1:<key id>:<base64 nonce + ciphertext>}; the key id (first bytes of the key's SHA-256) lets the previous
 * key decrypt old values during a rotation. Values without the prefix are legacy plain text.
 */
@Component
class WebhookKeyCipher {

    static final String PREFIX = "enc:v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final String currentId;
    private final Map<String, SecretKey> keys = new LinkedHashMap<>();

    WebhookKeyCipher(WebhookProperties properties) {
        var current = parse("WEBHOOK_KEY_ENCRYPTION_KEY", properties.keyEncryptionKey());
        var previous = parse("WEBHOOK_KEY_ENCRYPTION_KEY_PREVIOUS", properties.previousKeyEncryptionKey());
        currentId = current == null ? null : keyId(current);
        if (current != null) {
            keys.put(currentId, current);
        }
        if (previous != null) {
            keys.putIfAbsent(keyId(previous), previous);
        }
    }

    boolean configured() {
        return currentId != null;
    }

    String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        var key = requireCurrent();
        try {
            var nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(currentId.getBytes(StandardCharsets.US_ASCII));
            var sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            var payload = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(sealed, 0, payload, nonce.length, sealed.length);
            return PREFIX + currentId + ":" + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Encrypting a webhook signing key failed", ex);
        }
    }

    String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored; // legacy plain text, re-encrypted by WebhookKeyMigration at startup
        }
        var rest = stored.substring(PREFIX.length());
        var separator = rest.indexOf(':');
        if (separator < 0) {
            throw new IllegalStateException("Malformed encrypted webhook signing key");
        }
        var id = rest.substring(0, separator);
        var key = keys.get(id);
        if (key == null) {
            throw new IllegalStateException("No key-encryption key with id " + id
                    + " is configured (WEBHOOK_KEY_ENCRYPTION_KEY / _PREVIOUS)");
        }
        try {
            var payload = Base64.getDecoder().decode(rest.substring(separator + 1));
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 0, NONCE_BYTES));
            cipher.updateAAD(id.getBytes(StandardCharsets.US_ASCII));
            var plain = cipher.doFinal(payload, NONCE_BYTES, payload.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Decrypting a webhook signing key failed", ex);
        }
    }

    /** Plain text, or encrypted with a key other than the current one. */
    boolean needsReencryption(String stored) {
        return stored != null && configured() && !stored.startsWith(PREFIX + currentId + ":");
    }

    private SecretKey requireCurrent() {
        if (currentId == null) {
            throw new IllegalStateException("WEBHOOK_KEY_ENCRYPTION_KEY is not configured; webhook signing keys "
                    + "cannot be stored");
        }
        return keys.get(currentId);
    }

    private static SecretKey parse(String name, String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(name + " must be base64", ex);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException(name + " must be 32 bytes (AES-256), got " + bytes.length);
        }
        return new SecretKeySpec(bytes, "AES");
    }

    private static String keyId(SecretKey key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
