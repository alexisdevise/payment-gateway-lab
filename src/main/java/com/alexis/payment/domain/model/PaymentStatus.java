package com.alexis.payment.domain.model;

/**
 * <pre>
 * INITIATED -> AUTHORIZED -> CAPTURED -> REFUNDED
 *          \-> FAILED     \-> VOIDED
 * </pre>
 * A partial refund keeps the payment CAPTURED; it becomes REFUNDED once fully refunded.
 */
public enum PaymentStatus {
    INITIATED, AUTHORIZED, CAPTURED, REFUNDED, VOIDED, FAILED;

    public boolean canTransitionTo(PaymentStatus target) {
        return switch (this) {
            case INITIATED -> target == AUTHORIZED || target == FAILED;
            case AUTHORIZED -> target == CAPTURED || target == VOIDED;
            case CAPTURED -> target == REFUNDED;
            case REFUNDED, VOIDED, FAILED -> false;
        };
    }
}
