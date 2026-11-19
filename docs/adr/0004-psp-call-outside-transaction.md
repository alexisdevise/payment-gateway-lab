# ADR-0004 — No DB transaction during PSP calls, optimistic locking

- Status: accepted
- Date: 2026-11-19

## Context
A PSP call takes 200 ms to several seconds. Holding a DB transaction (and a row lock) during that time exhausts
the connection pool under load and blocks concurrent readers.

## Decision
Every use case follows the same three steps:
1. short transaction: load (or insert INITIATED, committed **before** the PSP call — it is the evidence to reconcile
   if we crash mid-call);
2. PSP call, no transaction, no lock;
3. short transaction: apply the result, guarded by `@Version` (optimistic lock) + an explicit version check, and
   append the operation journal row in the same transaction.

Losing the race returns `409 concurrent_update`; the client re-reads the payment.

## Consequences
- + Connection pool sized by DB work, not by PSP latency.
- − Two concurrent captures may both reach the PSP. Safe because the PSP idempotency key is identical
  (`{paymentId}:capture`), so the PSP performs it once; the loser gets 409.

## Rejected
- `SELECT ... FOR UPDATE` across the PSP call: simplest correctness, worst scalability.
- Outbox + async worker for PSP calls: right answer for capture/refund at scale, but the checkout flow needs a
  synchronous authorization answer. Possible evolution for back-office operations.
