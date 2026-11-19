package com.alexis.payment.domain.exception;

/** Same Idempotency-Key reused with a different request body: a client bug, never silently accepted. */
public class IdempotencyConflictException extends DomainException {
    public IdempotencyConflictException(String key) {
        super("idempotency_key_reused", "Idempotency-Key " + key + " was already used with a different request");
    }
}
