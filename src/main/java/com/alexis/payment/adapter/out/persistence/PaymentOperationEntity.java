package com.alexis.payment.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Append-only journal row. Never updated. */
@Entity
@Table(name = "payment_operation")
public class PaymentOperationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(nullable = false, updatable = false)
    private String type;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(nullable = false, updatable = false)
    private boolean succeeded;

    @Column(name = "psp_reference", updatable = false)
    private String pspReference;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "failure_code", updatable = false)
    private String failureCode;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected PaymentOperationEntity() {
    }

    PaymentOperationEntity(UUID paymentId, String type, long amountMinor, String currency, boolean succeeded,
                           String pspReference, String idempotencyKey, String failureCode, Instant occurredAt) {
        this.paymentId = paymentId;
        this.type = type;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.succeeded = succeeded;
        this.pspReference = pspReference;
        this.idempotencyKey = idempotencyKey;
        this.failureCode = failureCode;
        this.occurredAt = occurredAt;
    }

    String getType() { return type; }
    long getAmountMinor() { return amountMinor; }
    String getCurrency() { return currency; }
    boolean isSucceeded() { return succeeded; }
    String getPspReference() { return pspReference; }
    String getIdempotencyKey() { return idempotencyKey; }
    String getFailureCode() { return failureCode; }
    Instant getOccurredAt() { return occurredAt; }
}
