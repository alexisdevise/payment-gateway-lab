package com.alexis.payment.domain.model;

public record MerchantId(String value) {
    public MerchantId {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("merchantId must be 1..64 chars");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
