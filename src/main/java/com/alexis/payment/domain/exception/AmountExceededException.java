package com.alexis.payment.domain.exception;

import com.alexis.payment.domain.model.Money;

public class AmountExceededException extends DomainException {
    public AmountExceededException(String operation, Money requested, Money available) {
        super("amount_exceeded", operation + " of " + requested + " exceeds available " + available);
    }
}
