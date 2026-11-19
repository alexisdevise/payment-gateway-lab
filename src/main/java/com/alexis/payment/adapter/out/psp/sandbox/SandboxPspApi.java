package com.alexis.payment.adapter.out.psp.sandbox;

/**
 * The PSP's own API contract (what an HTTP client would call). Kept as an interface so the connector mapping
 * (including what plaintext reaches the PSP) is unit-testable with a capturing fake.
 */
public interface SandboxPspApi {

    SandboxResult authorize(AuthorizeCall call);

    SandboxResult capture(String merchantAccount, String authorizationId, long amountMinor, String currency,
                          String idempotencyKey);

    SandboxResult cancel(String merchantAccount, String authorizationId, String idempotencyKey);

    SandboxResult refund(String merchantAccount, String authorizationId, long amountMinor, String currency,
                         String idempotencyKey);

    /** PSP-specific request built from the decrypted consumer payload. */
    record AuthorizeCall(String merchantAccount, long amountMinor, String currency, String paymentMethodToken,
                         String shopperEmail, String shopperName, String billingCountry, String merchantOrderRef,
                         String idempotencyKey) {
        @Override
        public String toString() {
            return "AuthorizeCall[merchantAccount=" + merchantAccount + ", amount=" + amountMinor + " " + currency
                    + ", shopper=REDACTED]";
        }
    }

    /** @param status "approved", "refused" or "error" (as a real PSP would send it) */
    record SandboxResult(String status, String pspReference, String refusalReason) {
    }
}
