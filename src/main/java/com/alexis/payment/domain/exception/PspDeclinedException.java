package com.alexis.payment.domain.exception;

import com.alexis.payment.domain.model.OperationType;

/** The PSP answered and refused a follow-up operation (capture/void/refund). The payment state is unchanged. */
public class PspDeclinedException extends DomainException {
    public PspDeclinedException(OperationType operation, String pspReason) {
        super("psp_declined", operation + " declined by PSP: " + pspReason);
    }
}
