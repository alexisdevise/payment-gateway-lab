package com.alexis.payment.adapter.out.crypto;

import com.alexis.payment.adapter.out.crypto.AesGcmPayloadCipher.PayloadDecryptionException;
import com.alexis.payment.domain.model.EncryptedPayload;
import com.alexis.payment.domain.model.IdempotencyKey;
import com.alexis.payment.domain.model.MerchantId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmPayloadCipherTest {

    private static final String KEY_1 = "OR+gyWqOKMP+WybfmCTzFW1LYLAZ6u2d5zCW46iv4fI=";
    private static final String KEY_2 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private final AesGcmPayloadCipher cipher = new AesGcmPayloadCipher(Map.of("k1", KEY_1, "k2", KEY_2));
    private final byte[] aad = AesGcmPayloadCipher.associatedData(new MerchantId("m1"), new IdempotencyKey("order-0001"));
    private final byte[] plaintext = "{\"customer\":{\"email\":\"jane@example.com\"}}".getBytes(StandardCharsets.UTF_8);

    @Test
    void round_trip() {
        EncryptedPayload encrypted = cipher.encrypt("k1", plaintext, aad);

        assertThat(cipher.decrypt(encrypted, aad)).isEqualTo(plaintext);
    }

    @Test
    void ciphertext_does_not_contain_plaintext_and_uses_a_fresh_iv() {
        EncryptedPayload a = cipher.encrypt("k1", plaintext, aad);
        EncryptedPayload b = cipher.encrypt("k1", plaintext, aad);

        assertThat(new String(a.ciphertext(), StandardCharsets.ISO_8859_1)).doesNotContain("jane@example.com");
        assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
    }

    @Test
    void payload_bound_to_another_payment_cannot_be_decrypted() {
        EncryptedPayload encrypted = cipher.encrypt("k1", plaintext, aad);
        byte[] otherPayment = AesGcmPayloadCipher.associatedData(new MerchantId("m1"), new IdempotencyKey("order-0002"));

        assertThatThrownBy(() -> cipher.decrypt(encrypted, otherPayment)).isInstanceOf(PayloadDecryptionException.class);
    }

    @Test
    void tampered_ciphertext_is_rejected() {
        byte[] bytes = cipher.encrypt("k1", plaintext, aad).ciphertext();
        bytes[bytes.length - 1] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(new EncryptedPayload(bytes, "k1"), aad))
                .isInstanceOf(PayloadDecryptionException.class);
    }

    @Test
    void wrong_or_unknown_key_is_rejected() {
        byte[] bytes = cipher.encrypt("k1", plaintext, aad).ciphertext();

        assertThatThrownBy(() -> cipher.decrypt(new EncryptedPayload(bytes, "k2"), aad))
                .isInstanceOf(PayloadDecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(new EncryptedPayload(bytes, "nope"), aad))
                .isInstanceOf(PayloadDecryptionException.class)
                .hasMessageNotContaining("jane");
    }

    @Test
    void keys_must_be_256_bits() {
        assertThatThrownBy(() -> new AesGcmPayloadCipher(Map.of("short", "AAECAwQFBgcICQoLDA0ODw==")))
                .isInstanceOf(IllegalStateException.class);
    }
}
