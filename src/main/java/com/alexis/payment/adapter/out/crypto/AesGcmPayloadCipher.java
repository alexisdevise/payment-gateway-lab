package com.alexis.payment.adapter.out.crypto;

import com.alexis.payment.domain.model.EncryptedPayload;
import com.alexis.payment.domain.model.IdempotencyKey;
import com.alexis.payment.domain.model.MerchantId;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AES-256-GCM with a key ring (keyId -> key) to support rotation. Wire format: IV(12) || ciphertext || tag(16).
 *
 * <p>Associated data = {@code merchantId|idempotencyKey}: the ciphertext is cryptographically bound to the payment
 * it was produced for. Copying a payload into another payment request makes decryption fail (tag mismatch).
 */
public final class AesGcmPayloadCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, SecretKey> keyRing;

    /** @param base64KeysById keyId -> base64 of a 32-byte key */
    public AesGcmPayloadCipher(Map<String, String> base64KeysById) {
        if (base64KeysById == null || base64KeysById.isEmpty()) {
            throw new IllegalStateException("payment.crypto.keys must define at least one key");
        }
        this.keyRing = base64KeysById.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> toKey(e.getKey(), e.getValue())));
    }

    public static byte[] associatedData(MerchantId merchantId, IdempotencyKey idempotencyKey) {
        return (merchantId.value() + "|" + idempotencyKey.value()).getBytes(StandardCharsets.UTF_8);
    }

    /** Used by consumer-side tooling and tests. The payment API itself never encrypts consumer data. */
    public EncryptedPayload encrypt(String keyId, byte[] plaintext, byte[] associatedData) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(keyId), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData);
            byte[] sealed = cipher.doFinal(plaintext);
            return new EncryptedPayload(ByteBuffer.allocate(IV_LENGTH + sealed.length).put(iv).put(sealed).array(), keyId);
        } catch (GeneralSecurityException e) {
            throw new PayloadDecryptionException("encryption failed", e);
        }
    }

    /**
     * Caller owns the returned buffer and should wipe it ({@code Arrays.fill(buf, (byte) 0)}) once mapped.
     *
     * @throws PayloadDecryptionException unknown key, tampered ciphertext or wrong associated data
     */
    public byte[] decrypt(EncryptedPayload payload, byte[] associatedData) {
        byte[] data = payload.ciphertext();
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(payload.keyId()), new GCMParameterSpec(TAG_BITS, data, 0, IV_LENGTH));
            cipher.updateAAD(associatedData);
            return cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH);
        } catch (GeneralSecurityException e) {
            // Never include payload bytes in the message.
            throw new PayloadDecryptionException("payload cannot be decrypted with key " + payload.keyId(), e);
        }
    }

    private SecretKey key(String keyId) {
        SecretKey key = keyRing.get(keyId);
        if (key == null) {
            throw new PayloadDecryptionException("unknown payload key id " + keyId, null);
        }
        return key;
    }

    private static SecretKey toKey(String keyId, String base64) {
        byte[] raw = Base64.getDecoder().decode(base64);
        if (raw.length != 32) {
            throw new IllegalStateException("Key " + keyId + " must be 256 bits, got " + raw.length * 8);
        }
        return new SecretKeySpec(raw, "AES");
    }

    public static class PayloadDecryptionException extends RuntimeException {
        public PayloadDecryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
