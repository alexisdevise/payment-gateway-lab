# ADR-0003 — Idempotency enforced by the database

- Status: accepted
- Date: 2026-11-19

## Context
Networks fail. A checkout app that times out will retry, and a double authorization is a real customer incident.
Retries must be safe at two levels: between consumer and us, and between us and the PSP.

## Decision
**Consumer -> payment API**
- `Idempotency-Key` header is mandatory on every write that creates something (authorize, refund).
- `UNIQUE (merchant_id, idempotency_key)` in PostgreSQL is the source of truth — no check-then-insert in code,
  which would be racy. A lost insert race (`DuplicateIdempotencyKeyException`) is turned into a replay.
- A SHA-256 **request fingerprint** is stored with the key. Same key + different body => `422 idempotency_key_reused`.
  Same key + same body => original result, `200` + `Idempotent-Replayed: true`.
- Capture and void are idempotent **by state** (capturing an already captured payment with the same amount is a no-op).
- Refunds can be partial and repeated, so each one has its own key, de-duplicated by a partial unique index
  `payment_operation (payment_id, idempotency_key) WHERE idempotency_key IS NOT NULL`.

**Payment API -> PSP**
- Every PSP call carries a key derived from our ids (`{paymentId}:authorize`, `{paymentId}:refund:{key}`...).
- If the PSP outcome is unknown (timeout/5xx), the payment **stays INITIATED** and we answer `503 + Retry-After`.
  The consumer retries with the same key; we call the PSP again with the **same** PSP key, so the PSP de-duplicates.

## Consequences
- + Exactly-once *effect* without distributed transactions.
- − Relies on PSPs honouring idempotency keys (all major ones do; the sandbox simulates it).
- − If the consumer never retries, a payment can stay INITIATED. Needed next: a reconciliation job that queries the
  PSP for stale INITIATED payments (not implemented yet).

## Rejected
- **Redis idempotency cache**: one more moving part, and the key must be durable as long as the payment anyway.
- **Idempotency key as primary key**: couples identity to a client-provided value; merchant scoping is cleaner.
