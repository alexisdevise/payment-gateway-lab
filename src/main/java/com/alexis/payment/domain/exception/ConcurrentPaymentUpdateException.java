package com.alexis.payment.domain.exception;

import com.alexis.payment.domain.model.PaymentId;

/** Optimistic lock lost: another request modified the payment meanwhile. The client should re-read and retry. */
public class ConcurrentPaymentUpdateException extends DomainException {
    public ConcurrentPaymentUpdateException(PaymentId id) {
        super("concurrent_update", "Payment " + id + " was modified concurrently");
    }
}
