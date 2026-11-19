# ADR-0002 — Zero-trust consumer data: blind proxy

- Status: accepted
- Date: 2026-11-19

## Context
PSPs need customer data (email, name, billing address, order reference) for fraud scoring and 3-D Secure.
That data belongs to the consumer apps (checkout, merchant back-office), which have **their own databases**.
The payment API must forward it to the PSP without becoming another place where it can leak.
Card data (PAN/CVV) must never reach us at all (PCI-DSS scope reduction).

## Decision
1. **Cards**: the browser/app tokenizes the card with the PSP SDK. We only receive a `psp_token`.
   `PspToken` rejects anything shaped like a PAN, as a guard against an integration mistake.
2. **Consumer data**: sent as `encrypted_consumer_payload` = AES-256-GCM(JSON), produced by the consumer app.
   - Stored as-is (`BYTEA`) in `payment_db`, next to its `payload_key_id`. No plaintext column exists.
   - **Associated data = `merchantId|Idempotency-Key`**: the ciphertext is bound to one payment request.
     A payload copied into another request fails authentication (tested).
   - Decrypted **only** inside the PSP connector, right before building the PSP request; the plaintext buffer is
     zeroed after mapping, and every DTO holding it has a redacted `toString()`.
   - A key ring (`keyId -> key`) allows rotation without re-encrypting history.
3. **Responses** never echo the token or the payload.
4. **Consumer authentication**: `X-Api-Key` -> consumer -> (channel, merchant). Only SHA-256 hashes are configured.
   The merchant scope comes from the credential, never from the body; another merchant's payment answers 404.

## Consequences
- + A dump of `payment_db` reveals amounts, statuses and PSP references, but no PII and no card data.
- + Merchants cannot read or move each other's payments.
- − Symmetric key shared between consumer and payment API: if a consumer leaks it, its own payloads are readable.
- − We cannot validate the payload at the door (that would mean decrypting outside the PSP adapter). An invalid
  payload is detected at authorization time and results in `FAILED / invalid_consumer_payload`.

## Next steps (not done yet)
- Envelope encryption with a KMS (Vault Transit / cloud KMS): per-payment data keys, consumer encrypts with the
  payment API **public** key (HPKE / RSA-OAEP + AES-GCM) so consumers cannot decrypt each other's data.
- Retention policy: crypto-shred `encrypted_payload` once the payment reaches a terminal state + N days.
- Replace API keys by mTLS or OAuth2 client credentials.
