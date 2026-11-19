package com.alexis.payment.domain.model;

import java.util.regex.Pattern;

/** Client-supplied key making a write request safe to retry. Scoped per merchant (or per payment for refunds). */
public record IdempotencyKey(String value) {
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._:-]{8,128}");

    public IdempotencyKey {
        if (value == null || !ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException("Idempotency-Key must match " + ALLOWED.pattern());
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
