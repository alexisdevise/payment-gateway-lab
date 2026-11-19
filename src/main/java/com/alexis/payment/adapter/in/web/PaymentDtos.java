package com.alexis.payment.adapter.in.web;

import com.alexis.payment.domain.model.PaymentTransaction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** HTTP contract. JSON is snake_case (see spring.jackson.property-naming-strategy). */
final class PaymentDtos {

    private PaymentDtos() {
    }

    /**
     * @param encryptedConsumerPayload base64 of IV || AES-256-GCM ciphertext || tag, AAD = "merchantId|Idempotency-Key"
     */
    record AuthorizePaymentRequest(
            @NotNull @Positive Long amountMinor,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotBlank @Size(max = 255) String pspToken,
            @NotBlank @Size(max = 22_000) String encryptedConsumerPayload,
            @NotBlank @Size(max = 64) String payloadKeyId) {

        @Override
        public String toString() {
            return "AuthorizePaymentRequest[amount=" + amountMinor + " " + currency + ", payload=REDACTED]";
        }
    }

    /** @param amountMinor null = capture the full authorized amount */
    record CaptureRequest(@Positive Long amountMinor) {
    }

    record RefundRequest(@NotNull @Positive Long amountMinor) {
    }

    /** No token, no payload: the response never echoes consumer data. */
    record PaymentResponse(UUID paymentId,
                           String status,
                           long amountMinor,
                           String currency,
                           long capturedAmountMinor,
                           long refundedAmountMinor,
                           String pspCode,
                           String pspReference,
                           String failureCode,
                           Instant createdAt,
                           Instant updatedAt) {

        static PaymentResponse from(PaymentTransaction p) {
            return new PaymentResponse(
                    p.id().value(),
                    p.status().name(),
                    p.amount().amountMinor(),
                    p.amount().currency().getCurrencyCode(),
                    p.capturedAmount().amountMinor(),
                    p.refundedAmount().amountMinor(),
                    p.pspCode(),
                    p.pspReference() == null ? null : p.pspReference().value(),
                    p.failureCode(),
                    p.createdAt(),
                    p.updatedAt());
        }
    }
}
