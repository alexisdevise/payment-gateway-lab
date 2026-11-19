# payment-gateway-lab

Zero-trust **Payment Proxy API** in **Java 21 / Spring Boot 3.4**, **hexagonal architecture**, PostgreSQL + Flyway,
designed to run on **Kubernetes** (`kind`).

> **Why this repo.** After 13 years designing payment and SaaS backends on .NET / Azure, I am putting the
> Java / Spring / cloud-native stack back into practice on a domain I know well: multi-PSP orchestration,
> idempotency, state machines and PII/PCI data isolation. It is a learning project, built in the open: the
> history (small PRs, ADRs, [AI log](./AI.md)) is part of the deliverable.

---

## What it does

```
 checkout app ──(ECOMMERCE key)──►  POST /api/v1/checkout/payments            ┐
   own DB (PII, cart)                 psp_token + encrypted_consumer_payload   │
                                                                               │   payment-gateway-lab        ┌──► PSP A
 merchant back-office ─(MERCHANT_BO)► POST /api/v1/merchant/payments/{id}/     ├─► domain state machine ──────┤
   own DB                              capture | void | refund                 │   payment_db (no PII, no PAN)└──► PSP B
                                                                               ┘   PSP contracts & routing
```

- **Blind proxy.** Consumer apps keep their data in **their own databases**. They send the payment API only a PSP
  card token and an AES-256-GCM encrypted payload (customer, billing, order ref). `payment_db` stores the
  ciphertext as-is; it is decrypted **in memory, only inside the PSP connector**, to build the PSP request.
  → [ADR-0002](./docs/adr/0002-zero-trust-consumer-data.md)
- **No card data.** Only PSP tokens. Anything that looks like a PAN is rejected at the door.
- **The payment API owns PSP contracts and routing.** Consumers never choose or talk to a PSP.
- **Two channels, isolated.** `ECOMMERCE` can only authorize/read; `MERCHANT_BO` can only capture/void/refund/read.
  The merchant scope comes from the API key, never from the body; other merchants' payments answer 404.
- **Idempotency enforced by PostgreSQL** (`UNIQUE (merchant_id, idempotency_key)` + request fingerprint), and
  propagated to the PSP so a retry after a timeout never double-charges. → [ADR-0003](./docs/adr/0003-idempotency-strategy.md)
- **No DB transaction held during PSP calls**; optimistic locking. → [ADR-0004](./docs/adr/0004-psp-call-outside-transaction.md)

### State machine (enforced in `PaymentTransaction`)

```
INITIATED ──► AUTHORIZED ──► CAPTURED ──► REFUNDED   (partial refunds keep CAPTURED)
    └──► FAILED     └──► VOIDED
```

## Code layout (hexagonal) → [ADR-0001](./docs/adr/0001-hexagonal-architecture.md)

```
com.alexis.payment
├── domain          model, ports (in/out), exceptions — zero framework imports (checked by ArchUnit)
├── application     PaymentService: use-case orchestration, framework-free
├── adapter
│   ├── in/web      REST controllers per channel, API-key filter, RFC 7807 errors
│   └── out
│       ├── persistence   JPA entities + adapter (Flyway owns the schema)
│       ├── psp           RoutingPspAdapter + one connector per PSP (Sandbox simulated)
│       └── crypto        AES-256-GCM key ring
└── config          composition root
```

## Run it locally

**Everything in Docker** (only Docker needed):

```bash
docker compose up -d --build                           # PostgreSQL 16 + the API on :8080 (profile local)
```

**Or the API from your IDE / Maven** (JDK 21 + Docker):

```bash
docker compose up -d postgres                          # PostgreSQL 16 (payment_db)
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The image is a multi-stage build (JDK to build, JRE to run), layered jar, non-root user, heap sized from the
container memory limit, healthcheck on `/actuator/health/readiness`.

The `local` profile holds **dev-only** keys (public on purpose, never used elsewhere) and seeds a demo PSP contract.

```bash
KEY=order-$(date +%s)
PAYLOAD=$(java tools/EncryptPayload.java OR+gyWqOKMP+WybfmCTzFW1LYLAZ6u2d5zCW46iv4fI= merchant-demo $KEY tools/sample-payload.json)

# ECOMMERCE: authorize
curl -s -X POST localhost:8080/api/v1/checkout/payments \
  -H "X-Api-Key: dev-ecommerce-key-local" -H "Idempotency-Key: $KEY" -H "Content-Type: application/json" \
  -d "{\"amount_minor\":4990,\"currency\":\"EUR\",\"psp_token\":\"tok_visa_4242\",\"encrypted_consumer_payload\":\"$PAYLOAD\",\"payload_key_id\":\"dev-2026-11\"}"

# MERCHANT_BO: capture then partial refund
curl -s -X POST localhost:8080/api/v1/merchant/payments/<payment_id>/capture -H "X-Api-Key: dev-merchant-bo-key-change-me"
curl -s -X POST localhost:8080/api/v1/merchant/payments/<payment_id>/refund \
  -H "X-Api-Key: dev-merchant-bo-key-change-me" -H "Idempotency-Key: refund-$KEY" -H "Content-Type: application/json" \
  -d '{"amount_minor":1000}'
```

Sandbox PSP behaviour: token `tok_sandbox_decline` → `FAILED / card_declined`; `tok_sandbox_unavailable` →
`503 + Retry-After`, payment stays `INITIATED` until retried with the same key.

## Tests

```bash
./mvnw test                                   # everything (integration tests need Docker)
./mvnw test -Dtest=PaymentTransactionTest     # targeted
```

| Test | What it proves |
|---|---|
| `PaymentTransactionTest` | state machine, amounts, partial capture/refund, PAN guard |
| `AesGcmPayloadCipherTest` | round trip, fresh IV, tamper detection, AAD binding, key rotation |
| `PaymentServiceTest` | idempotent replay, key reuse conflict, PSP timeout + retry, lost insert race, optimistic lock |
| `HexagonalArchitectureTest` | CLAUDE.md rules as code (ArchUnit) |
| `PaymentApiIntegrationTest` | real PostgreSQL (Testcontainers): lifecycle, 8 concurrent same-key requests → 1 row, channel isolation, **raw DB row contains no customer data** |

## Roadmap

- [x] Hexagonal domain, AES-256-GCM payload, PSP routing port + sandbox connector
- [x] PostgreSQL schema (Flyway), DB-enforced idempotency, operation journal
- [x] Unit, architecture and Testcontainers integration tests
- [x] ADRs, `CLAUDE.md`, `AI.md`
- [x] Multi-stage `Dockerfile`, app service in `docker-compose.yml`
- [ ] Kubernetes manifests on `kind` (Deployment, Service, ConfigMap, Secret, probes, limits)
- [ ] Observability: Micrometer business metrics, Prometheus, Grafana dashboard, alert rules (logs are already ECS JSON)
- [ ] GitHub Actions: build, tests, image, deploy to kind
- [ ] SQL performance: 1M+ rows, merchant reporting queries, index + `EXPLAIN ANALYZE` before/after
- [ ] Reconciliation job for stale `INITIATED` payments; outbox for back-office operations
- [ ] Helm chart, then ArgoCD; Terraform

## Engineering log

- [`CLAUDE.md`](./CLAUDE.md) — rules given to the AI agent.
- [`AI.md`](./AI.md) — what was delegated to AI, what was wrong, how it was verified.
- [`docs/adr/`](./docs/adr) — architecture decisions and the alternatives rejected.
