package com.alexis.payment.domain.model;

import com.alexis.payment.domain.exception.AmountExceededException;
import com.alexis.payment.domain.exception.InvalidStateTransitionException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Aggregate root. Every state transition goes through this class; nothing else mutates a payment.
 *
 * <p>Follow-up operations use a two-step pattern: {@code assertXxx()} is called <b>before</b> the PSP call
 * (so we never ask the PSP for something the state machine forbids), then the mutation is applied with the
 * PSP result. Each PSP outcome is journaled as a {@link PaymentOperation}, collected by the repository.
 */
public final class PaymentTransaction {

    private final PaymentId id;
    private final MerchantId merchantId;
    private final IdempotencyKey idempotencyKey;
    private final String requestFingerprint;
    private final Money amount;
    private final String pspCode;
    private final PspToken pspToken;
    private final EncryptedPayload encryptedPayload;
    private final Instant createdAt;
    /** Opaque persistence token for optimistic locking (the PSP call happens between load and save). */
    private final long version;

    private PaymentStatus status;
    private Money capturedAmount;
    private Money refundedAmount;
    private PspReference pspReference;
    private String failureCode;
    private Instant updatedAt;

    private final List<PaymentOperation> pendingOperations = new ArrayList<>();

    private PaymentTransaction(PaymentId id, MerchantId merchantId, IdempotencyKey idempotencyKey,
                               String requestFingerprint, Money amount, String pspCode, PspToken pspToken,
                               EncryptedPayload encryptedPayload, PaymentStatus status, Money capturedAmount,
                               Money refundedAmount, PspReference pspReference, String failureCode,
                               Instant createdAt, Instant updatedAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.pspCode = Objects.requireNonNull(pspCode, "pspCode");
        this.pspToken = Objects.requireNonNull(pspToken, "pspToken");
        this.encryptedPayload = Objects.requireNonNull(encryptedPayload, "encryptedPayload");
        this.status = Objects.requireNonNull(status, "status");
        this.capturedAmount = Objects.requireNonNull(capturedAmount, "capturedAmount");
        this.refundedAmount = Objects.requireNonNull(refundedAmount, "refundedAmount");
        this.pspReference = pspReference;
        this.failureCode = failureCode;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    public static PaymentTransaction initiate(PaymentId id, MerchantId merchantId, IdempotencyKey idempotencyKey,
                                              String requestFingerprint, Money amount, String pspCode,
                                              PspToken pspToken, EncryptedPayload encryptedPayload, Instant now) {
        if (amount.isZero()) {
            throw new IllegalArgumentException("amountMinor must be > 0");
        }
        Money zero = Money.zero(amount.currency());
        return new PaymentTransaction(id, merchantId, idempotencyKey, requestFingerprint, amount, pspCode, pspToken,
                encryptedPayload, PaymentStatus.INITIATED, zero, zero, null, null, now, now, 0L);
    }

    /** Rebuilds an aggregate from storage. No invariant re-check beyond nullity: storage is trusted (DB constraints). */
    public static PaymentTransaction rehydrate(PaymentId id, MerchantId merchantId, IdempotencyKey idempotencyKey,
                                               String requestFingerprint, Money amount, String pspCode,
                                               PspToken pspToken, EncryptedPayload encryptedPayload,
                                               PaymentStatus status, Money capturedAmount, Money refundedAmount,
                                               PspReference pspReference, String failureCode, Instant createdAt,
                                               Instant updatedAt, long version) {
        return new PaymentTransaction(id, merchantId, idempotencyKey, requestFingerprint, amount, pspCode, pspToken,
                encryptedPayload, status, capturedAmount, refundedAmount, pspReference, failureCode, createdAt,
                updatedAt, version);
    }

    // ---------------------------------------------------------------- authorization

    public void markAuthorized(PspReference reference, Instant now) {
        transitionTo(PaymentStatus.AUTHORIZED, now);
        this.pspReference = Objects.requireNonNull(reference, "reference");
        journal(OperationType.AUTHORIZATION, amount, true, reference, null, null, now);
    }

    public void markAuthorizationDeclined(String reasonCode, Instant now) {
        transitionTo(PaymentStatus.FAILED, now);
        this.failureCode = reasonCode;
        journal(OperationType.AUTHORIZATION, amount, false, null, null, reasonCode, now);
    }

    // ---------------------------------------------------------------- capture

    public void assertCapturable(Money requested) {
        requireTransition(PaymentStatus.CAPTURED);
        requirePositive(requested);
        if (requested.isGreaterThan(amount)) {
            throw new AmountExceededException("Capture", requested, amount);
        }
    }

    public void capture(Money requested, PspReference captureReference, Instant now) {
        assertCapturable(requested);
        transitionTo(PaymentStatus.CAPTURED, now);
        this.capturedAmount = requested;
        journal(OperationType.CAPTURE, requested, true, captureReference, null, null, now);
    }

    // ---------------------------------------------------------------- void

    public void assertVoidable() {
        requireTransition(PaymentStatus.VOIDED);
    }

    public void voidAuthorization(PspReference voidReference, Instant now) {
        transitionTo(PaymentStatus.VOIDED, now);
        journal(OperationType.VOID, amount, true, voidReference, null, null, now);
    }

    // ---------------------------------------------------------------- refund

    public void assertRefundable(Money requested) {
        if (status != PaymentStatus.CAPTURED) {
            throw new InvalidStateTransitionException(status, PaymentStatus.REFUNDED);
        }
        requirePositive(requested);
        Money remaining = remainingRefundable();
        if (requested.isGreaterThan(remaining)) {
            throw new AmountExceededException("Refund", requested, remaining);
        }
    }

    public void refund(Money requested, PspReference refundReference, IdempotencyKey operationKey, Instant now) {
        assertRefundable(requested);
        this.refundedAmount = refundedAmount.plus(requested);
        if (refundedAmount.equals(capturedAmount)) {
            transitionTo(PaymentStatus.REFUNDED, now);
        } else {
            this.updatedAt = now;
        }
        journal(OperationType.REFUND, requested, true, refundReference, operationKey, null, now);
    }

    public Money remainingRefundable() {
        return capturedAmount.minus(refundedAmount);
    }

    // ---------------------------------------------------------------- declined follow-ups

    /** PSP refused a capture/void/refund: state unchanged, but the attempt is journaled for audit. */
    public void recordDeclinedOperation(OperationType type, Money requested, String reasonCode,
                                        IdempotencyKey operationKey, Instant now) {
        if (type == OperationType.AUTHORIZATION) {
            throw new IllegalArgumentException("Use markAuthorizationDeclined for authorizations");
        }
        this.updatedAt = now;
        journal(type, requested, false, null, operationKey, reasonCode, now);
    }

    /** Returns and clears the operations produced since load, for the repository to append. */
    public List<PaymentOperation> pullPendingOperations() {
        List<PaymentOperation> copy = List.copyOf(pendingOperations);
        pendingOperations.clear();
        return copy;
    }

    // ---------------------------------------------------------------- internals

    private void transitionTo(PaymentStatus target, Instant now) {
        requireTransition(target);
        this.status = target;
        this.updatedAt = now;
    }

    private void requireTransition(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(status, target);
        }
    }

    private void requirePositive(Money requested) {
        amount.requireSameCurrency(requested);
        if (requested.isZero()) {
            throw new IllegalArgumentException("amountMinor must be > 0");
        }
    }

    private void journal(OperationType type, Money opAmount, boolean succeeded, PspReference ref,
                         IdempotencyKey opKey, String failure, Instant now) {
        pendingOperations.add(new PaymentOperation(type, opAmount, succeeded, ref, opKey, failure, now));
    }

    // ---------------------------------------------------------------- accessors

    public PaymentId id() { return id; }
    public MerchantId merchantId() { return merchantId; }
    public IdempotencyKey idempotencyKey() { return idempotencyKey; }
    public String requestFingerprint() { return requestFingerprint; }
    public Money amount() { return amount; }
    public String pspCode() { return pspCode; }
    public PspToken pspToken() { return pspToken; }
    public EncryptedPayload encryptedPayload() { return encryptedPayload; }
    public PaymentStatus status() { return status; }
    public Money capturedAmount() { return capturedAmount; }
    public Money refundedAmount() { return refundedAmount; }
    public PspReference pspReference() { return pspReference; }
    public String failureCode() { return failureCode; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }

    @Override
    public String toString() {
        // Deliberately no payload / token content: this object may end up in logs.
        return "PaymentTransaction[id=" + id + ", merchant=" + merchantId + ", status=" + status + ", amount=" + amount + "]";
    }
}
