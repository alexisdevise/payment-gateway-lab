package com.alexis.payment.adapter.out.psp;

/**
 * Plaintext shape of the consumer payload, once decrypted inside a PSP connector. Lives only on the stack of the
 * PSP call. {@link #toString()} is redacted so an accidental log statement cannot leak PII.
 */
public record ConsumerPayload(String orderReference,
                              Customer customer,
                              BillingAddress billingAddress) {

    public record Customer(String email, String firstName, String lastName) {
        @Override
        public String toString() {
            return "Customer[REDACTED]";
        }
    }

    public record BillingAddress(String line1, String postalCode, String city, String countryCode) {
        @Override
        public String toString() {
            return "BillingAddress[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "ConsumerPayload[REDACTED]";
    }
}
