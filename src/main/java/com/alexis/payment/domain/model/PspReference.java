package com.alexis.payment.domain.model;

/** Identifier returned by the PSP for an operation (authorization id, capture id, refund id...). */
public record PspReference(String value) {
    public PspReference {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("pspReference must be 1..128 chars");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
