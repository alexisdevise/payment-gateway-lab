package com.alexis.payment.domain.exception;

/**
 * Raised by the persistence port when the unique constraint on the idempotency key fires,
 * i.e. a concurrent request with the same key was inserted first. The application then replays it.
 */
public class DuplicateIdempotencyKeyException extends DomainException {
    public DuplicateIdempotencyKeyException(String key) {
        super("duplicate_idempotency_key", "Idempotency-Key " + key + " already recorded");
    }
}
