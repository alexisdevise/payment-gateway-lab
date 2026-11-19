package com.alexis.payment.domain.exception;

import com.alexis.payment.domain.model.PaymentStatus;

public class InvalidStateTransitionException extends DomainException {
    public InvalidStateTransitionException(PaymentStatus from, PaymentStatus to) {
        super("invalid_state_transition", "Cannot move payment from " + from + " to " + to);
    }
}
