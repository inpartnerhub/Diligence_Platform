package com.inpartnerhub.diligence.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

/** Issue #64: webhook signing keys are encrypted at rest with AES-256-GCM and survive a key rotation. */
class WebhookKeyCipherTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String KEY_B = Base64.getEncoder().encodeToString(filled((byte) 7));

    private static byte[] filled(byte value) {
        var bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    private static WebhookKeyCipher cipher(String current, String previous) {
        return new WebhookKeyCipher(new WebhookProperties(false, current, previous, null));
    }

    @Test
    void encryptsWithAFreshNonceAndDecryptsBack() {
        var cipher = cipher(KEY_A, null);
        var first = cipher.encrypt("whsec_secret");
        var second = cipher.encrypt("whsec_secret");

        assertThat(first).startsWith(WebhookKeyCipher.PREFIX).doesNotContain("whsec_secret").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("whsec_secret");
        assertThat(cipher.decrypt(second)).isEqualTo("whsec_secret");
        assertThat(cipher.needsReencryption(first)).isFalse();
    }

    @Test
    void rejectsTamperedValuesAndUnknownKeys() {
        var encrypted = cipher(KEY_A, null).encrypt("whsec_secret");
        var payload = encrypted.substring(encrypted.lastIndexOf(':') + 1);
        var bytes = Base64.getDecoder().decode(payload);
        bytes[bytes.length - 1] ^= 1;
        var tampered = encrypted.substring(0, encrypted.lastIndexOf(':') + 1)
                + Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> cipher(KEY_A, null).decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(KEY_B, null).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No key-encryption key");
    }

    @Test
    void readsLegacyPlainTextAndOldKeysDuringARotation() {
        var old = cipher(KEY_A, null).encrypt("whsec_old");
        var rotated = cipher(KEY_B, KEY_A);

        assertThat(rotated.decrypt("whsec_plain")).isEqualTo("whsec_plain");
        assertThat(rotated.needsReencryption("whsec_plain")).isTrue();
        assertThat(rotated.decrypt(old)).isEqualTo("whsec_old");
        assertThat(rotated.needsReencryption(old)).isTrue();
        var reencrypted = rotated.encrypt(rotated.decrypt(old));
        assertThat(rotated.needsReencryption(reencrypted)).isFalse();
        assertThat(cipher(KEY_B, null).decrypt(reencrypted)).isEqualTo("whsec_old"); // previous key no longer needed
    }

    @Test
    void refusesToStoreWithoutAKeyAndValidatesKeys() {
        var none = cipher(null, null);
        assertThat(none.configured()).isFalse();
        assertThat(none.needsReencryption("whsec_plain")).isFalse();
        assertThatThrownBy(() -> none.encrypt("whsec")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEBHOOK_KEY_ENCRYPTION_KEY");

        assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString(new byte[16]), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> cipher("not base64!", null)).isInstanceOf(IllegalStateException.class);
    }
}
