package com.alexis.payment.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Append-only journal entry of a PSP call outcome. Gives an audit trail and per-operation idempotency
 * (two refunds with distinct keys are two operations; the same key twice is a replay).
 *
 * @param pspReference   null when declined
 * @param idempotencyKey null for operations that are idempotent by state (authorization, capture, void)
 * @param failureCode    null when succeeded
 */
public record PaymentOperation(OperationType type,
                               Money amount,
                               boolean succeeded,
                               PspReference pspReference,
                               IdempotencyKey idempotencyKey,
                               String failureCode,
                               Instant occurredAt) {
    public PaymentOperation {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
