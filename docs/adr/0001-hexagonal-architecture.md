# ADR-0001 — Hexagonal architecture (ports & adapters)

- Status: accepted
- Date: 2026-11-19

## Context
The service is a proxy between consumer apps and several PSPs. What changes most often is at the edges:
a new PSP, a new consumer channel, a new storage concern. The payment state machine itself is stable and is
where bugs cost real money.

## Decision
- `domain` (model, ports, exceptions) and `application` (use-case orchestration) have **no framework dependency**.
- Inbound adapter: REST (`adapter.in.web`), one controller per consumer channel.
- Outbound adapters: JPA (`adapter.out.persistence`), PSP connectors (`adapter.out.psp`), crypto (`adapter.out.crypto`).
- Wiring happens in a single composition root (`config.PaymentConfiguration`).
- The rules are **executed**, not just written: `HexagonalArchitectureTest` (ArchUnit) fails the build if the
  domain imports Spring/JPA/Jackson, or if anything other than a PSP adapter touches the payload cipher.

## Consequences
- + The state machine and the idempotency logic are unit-tested with plain fakes (no Spring context, ms runtime).
- + Adding a PSP = one `PspConnector` implementation, nothing else changes.
- − Mapping code (entity <-> aggregate) is duplicated boilerplate. Accepted: it keeps JPA annotations out of the
  domain and makes the persisted shape explicit.
- − The aggregate carries a `version` field, a persistence concern leaking into the domain. Accepted pragmatically:
  optimistic locking across a PSP call needs it (see ADR-0004).

## Rejected
- **Layered (controller/service/repository) with JPA entities as domain model**: faster to write, but lazy-loading
  and `@Entity` constraints end up shaping business rules.
- **Separate Maven modules per layer**: stronger isolation, but too much ceremony for one service. ArchUnit gives
  the same guarantee at a fraction of the cost.
