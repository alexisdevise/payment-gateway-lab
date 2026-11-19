package com.alexis.payment.domain.exception;

/** Base class of business errors. {@link #code()} is a stable, machine-readable identifier exposed to clients. */
public abstract class DomainException extends RuntimeException {
    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
