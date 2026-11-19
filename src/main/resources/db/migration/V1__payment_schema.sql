-- payment_db: zero plaintext consumer data, zero card data.
-- Consumer apps (checkout, merchant back-office) own their own databases; nothing here joins to them.

CREATE TABLE psp_contract (
    id                   UUID         PRIMARY KEY,
    merchant_id          VARCHAR(64)  NOT NULL,
    psp_code             VARCHAR(32)  NOT NULL,
    -- Account identifier at the PSP. Credentials are NOT stored here: they come from secrets.
    merchant_account_ref VARCHAR(128) NOT NULL,
    priority             INTEGER      NOT NULL DEFAULT 100,
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_psp_contract_merchant_psp UNIQUE (merchant_id, psp_code)
);

CREATE TABLE payment_transaction (
    id                  UUID         PRIMARY KEY,
    merchant_id         VARCHAR(64)  NOT NULL,
    idempotency_key     VARCHAR(128) NOT NULL,
    -- SHA-256 hex of the request body: same key + different body => 422, never a silent replay.
    request_fingerprint VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    amount_minor        BIGINT       NOT NULL,
    currency            VARCHAR(3)   NOT NULL,
    captured_minor      BIGINT       NOT NULL DEFAULT 0,
    refunded_minor      BIGINT       NOT NULL DEFAULT 0,
    psp_code            VARCHAR(32)  NOT NULL,
    psp_token           VARCHAR(255) NOT NULL,
    psp_reference       VARCHAR(128),
    -- AES-256-GCM ciphertext produced by the consumer app (IV || data || tag). Opaque to this database.
    encrypted_payload   BYTEA        NOT NULL,
    payload_key_id      VARCHAR(64)  NOT NULL,
    failure_code        VARCHAR(64),
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    version             BIGINT       NOT NULL,

    -- Idempotency is enforced by the database, not by a check-then-insert in code (race-proof).
    CONSTRAINT uq_payment_merchant_idempotency UNIQUE (merchant_id, idempotency_key),
    CONSTRAINT ck_payment_status CHECK (status IN ('INITIATED','AUTHORIZED','CAPTURED','REFUNDED','VOIDED','FAILED')),
    CONSTRAINT ck_payment_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_amount CHECK (amount_minor > 0),
    -- Money invariants duplicated in the DB as a last line of defence against a bug in the domain.
    CONSTRAINT ck_payment_captured CHECK (captured_minor BETWEEN 0 AND amount_minor),
    CONSTRAINT ck_payment_refunded CHECK (refunded_minor BETWEEN 0 AND captured_minor)
);

-- Append-only journal of every PSP call outcome (audit trail + per-refund idempotency).
CREATE TABLE payment_operation (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id      UUID         NOT NULL REFERENCES payment_transaction (id),
    type            VARCHAR(16)  NOT NULL,
    amount_minor    BIGINT       NOT NULL,
    currency        VARCHAR(3)   NOT NULL,
    succeeded       BOOLEAN      NOT NULL,
    psp_reference   VARCHAR(128),
    idempotency_key VARCHAR(128),
    failure_code    VARCHAR(64),
    occurred_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_operation_type CHECK (type IN ('AUTHORIZATION','CAPTURE','VOID','REFUND')),
    CONSTRAINT ck_operation_amount CHECK (amount_minor > 0)
);

CREATE INDEX ix_operation_payment ON payment_operation (payment_id);

-- Partial unique index: only operations carrying a client key (refunds) are de-duplicated.
CREATE UNIQUE INDEX uq_operation_payment_idempotency
    ON payment_operation (payment_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- NOTE: no index yet on (merchant_id, status, created_at). It is added later together with an
-- EXPLAIN ANALYZE before/after on a large dataset (see README "SQL performance").
