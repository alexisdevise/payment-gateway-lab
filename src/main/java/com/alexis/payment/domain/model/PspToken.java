package com.alexis.payment.domain.model;

import java.util.regex.Pattern;

/** Opaque token issued by the external PSP (card tokenized client-side by the PSP SDK). Never a PAN. */
public record PspToken(String value) {
    private static final Pattern LOOKS_LIKE_PAN = Pattern.compile("\\d{12,19}");

    public PspToken {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException("pspToken must be 1..255 chars");
        }
        if (LOOKS_LIKE_PAN.matcher(value.replaceAll("[\\s-]", "")).matches()) {
            // Defensive guard: this API is out of PCI-DSS card-data scope and must never receive a card number.
            throw new IllegalArgumentException("pspToken looks like a raw card number; only PSP tokens are accepted");
        }
    }

    @Override
    public String toString() {
        return value.length() <= 8 ? "****" : value.substring(0, 4) + "****";
    }
}
