package com.alexis.payment.domain.exception;

import com.alexis.payment.domain.model.OperationType;
import com.alexis.payment.domain.model.PaymentId;

/**
 * The PSP outcome is unknown (timeout, 5xx). The payment keeps its current state and the client must retry
 * with the same Idempotency-Key: the retry reuses the same PSP idempotency key, so the PSP never charges twice.
 */
public class PspUnavailableException extends DomainException {
    public PspUnavailableException(PaymentId id, OperationType operation) {
        super("psp_unavailable", operation + " outcome unknown for payment " + id + ", retry with the same Idempotency-Key");
    }
}
