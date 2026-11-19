package com.alexis.payment.application;

import com.alexis.payment.domain.port.in.AuthorizePaymentUseCase.AuthorizePaymentCommand;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of the business content of a request. Stored next to the idempotency key so that a replay with the
 * same key but a different body is rejected instead of silently returning an unrelated payment.
 */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String of(AuthorizePaymentCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, Long.toString(command.amount().amountMinor()));
            update(digest, command.amount().currency().getCurrencyCode());
            update(digest, command.pspToken().value());
            update(digest, command.encryptedPayload().keyId());
            digest.update(command.encryptedPayload().ciphertext());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0); // separator: avoids ("ab","c") == ("a","bc")
    }
}
