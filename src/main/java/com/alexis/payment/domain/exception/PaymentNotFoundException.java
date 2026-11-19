package com.alexis.payment.domain.exception;

import com.alexis.payment.domain.model.PaymentId;

/** Also raised when the payment exists but belongs to another merchant: no existence oracle across tenants. */
public class PaymentNotFoundException extends DomainException {
    public PaymentNotFoundException(PaymentId id) {
        super("payment_not_found", "Payment " + id + " not found");
    }
}
