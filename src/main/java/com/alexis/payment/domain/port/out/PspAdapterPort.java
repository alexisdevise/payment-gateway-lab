package com.alexis.payment.domain.port.out;

import com.alexis.payment.domain.model.EncryptedPayload;
import com.alexis.payment.domain.model.IdempotencyKey;
import com.alexis.payment.domain.model.MerchantId;
import com.alexis.payment.domain.model.Money;
import com.alexis.payment.domain.model.PaymentId;
import com.alexis.payment.domain.model.PspContract;
import com.alexis.payment.domain.model.PspReference;
import com.alexis.payment.domain.model.PspToken;

/**
 * Outbound port to external PSPs. Implementations translate to each PSP contract and are the only place where the
 * {@link EncryptedPayload} is decrypted (in memory, never logged).
 *
 * <p>Every call carries a {@code pspIdempotencyKey} derived from our own ids, so a retry after an unknown outcome
 * can never produce a second charge/capture/refund at the PSP.
 */
public interface PspAdapterPort {

    PspResponse authorize(AuthorizationRequest request);

    PspResponse capture(PspContract contract, PspReference authorization, Money amount, String pspIdempotencyKey);

    PspResponse voidAuthorization(PspContract contract, PspReference authorization, String pspIdempotencyKey);

    PspResponse refund(PspContract contract, PspReference authorization, Money amount, String pspIdempotencyKey);

    /**
     * merchantId + idempotencyKey are the AES-GCM associated data the consumer bound the ciphertext to:
     * a payload copied into another payment fails to decrypt.
     */
    record AuthorizationRequest(PspContract contract, PaymentId paymentId, MerchantId merchantId,
                                IdempotencyKey idempotencyKey, Money amount, PspToken pspToken,
                                EncryptedPayload encryptedPayload) {
        public String pspIdempotencyKey() {
            return paymentId + ":authorize";
        }
    }

    /** Closed set of outcomes: the compiler forces every caller to handle all three. */
    sealed interface PspResponse {
        record Approved(PspReference reference) implements PspResponse {
        }

        /** The PSP answered and refused (card declined, invalid payload...). Final outcome. */
        record Declined(String reasonCode) implements PspResponse {
        }

        /** Timeout / 5xx / network: outcome unknown, safe to retry with the same PSP idempotency key. */
        record Unavailable(String reason) implements PspResponse {
        }
    }
}
