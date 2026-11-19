# CLAUDE.md - Architecture & Security Guardrails

## Context
This repository (`payment-gateway-lab`) is a lab for try transitioning from .NET/Azure to Java 21 / Spring Boot / Kubernetes / Helm / Terraform).

## Strict Architectural Rules
- **Hexagonal Architecture (Ports & Adapters):**
  - `com.alexis.payment.domain` MUST remain 100% framework-agnostic (ZERO imports from `org.springframework.*`, `jakarta.persistence.*`, or Jackson).
  - Domain state transitions (`INITIATED` -> `AUTHORIZED` -> `CAPTURED` -> `REFUNDED` / `VOIDED` / `FAILED`) MUST be enforced inside `PaymentTransaction`.
- **Two Distinct Consumer Channels:**
  - `ECOMMERCE`: initiates payment authorization (`POST /api/v1/checkout/payments`), receives PSP token.
  - `MERCHANT_BO`: performs capture, void, and refund (`POST /api/v1/merchant/payments/{id}/capture|refund`).

## Blind Proxy & Zero-Trust Data Rules
- **No Plaintext Consumer Data in DB:** Never store customer PII, cart details, or billing info in plaintext in `payment_db`. Consumer apps send an opaque `encrypted_consumer_payload` (AES-256-GCM).
- **No Raw Card Data (PAN/CVV):** Only external PSP tokens (`psp_token`) transit through this API.
- **Ephemeral Decryption:** `encrypted_consumer_payload` is decrypted strictly in-memory inside outbound PSP adapters (`PspAdapterPort`) right before forging the PSP API request, and MUST NEVER be logged.
- **Monetary Values:** Always use `long` in minor units (cents) + ISO-4217 currency code. Never `float` or `double`.

## Code & Token Efficiency Rules
- Use Java 21 `record` for immutable commands/DTOs and `sealed interface` for PSP responses.
- Return RFC 7807 `ProblemDetail` for all HTTP errors.
- Do not scan `target/`, `.git/`, or `deploy/` unless explicitly asked.
- Run targeted tests during iteration: `mvn test -Dtest=ClassName`.