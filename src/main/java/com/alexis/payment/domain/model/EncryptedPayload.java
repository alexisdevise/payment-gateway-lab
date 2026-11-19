package com.alexis.payment.domain.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * Opaque consumer data (customer, billing address, cart...) encrypted by the consumer app with AES-256-GCM.
 * The domain never sees plaintext: only the outbound PSP adapter decrypts it, in memory, right before the PSP call.
 *
 * @param ciphertext IV (12 bytes) || ciphertext || GCM tag (16 bytes)
 * @param keyId      identifier of the key in the key ring, to allow rotation
 */
public record EncryptedPayload(byte[] ciphertext, String keyId) {
    public static final int MIN_LENGTH = 12 + 16 + 1;
    public static final int MAX_LENGTH = 16 * 1024;

    public EncryptedPayload {
        Objects.requireNonNull(ciphertext, "ciphertext");
        if (ciphertext.length < MIN_LENGTH || ciphertext.length > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "encrypted payload size must be " + MIN_LENGTH + ".." + MAX_LENGTH + " bytes");
        }
        if (keyId == null || keyId.isBlank() || keyId.length() > 64) {
            throw new IllegalArgumentException("payloadKeyId must be 1..64 chars");
        }
        ciphertext = ciphertext.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EncryptedPayload other
                && keyId.equals(other.keyId)
                && Arrays.equals(ciphertext, other.ciphertext);
    }

    @Override
    public int hashCode() {
        return 31 * keyId.hashCode() + Arrays.hashCode(ciphertext);
    }

    @Override
    public String toString() {
        return "EncryptedPayload[keyId=" + keyId + ", bytes=" + ciphertext.length + "]";
    }
}
