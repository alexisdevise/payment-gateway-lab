package com.alexis.payment.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** Persistence model only. Mapped to/from the domain aggregate in {@link PaymentPersistenceAdapter}. */
@Entity
@Table(name = "payment_transaction")
public class PaymentTransactionEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private String merchantId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, updatable = false)
    private String requestFingerprint;

    @Column(nullable = false)
    private String status;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "captured_minor", nullable = false)
    private long capturedMinor;

    @Column(name = "refunded_minor", nullable = false)
    private long refundedMinor;

    @Column(name = "psp_code", nullable = false, updatable = false)
    private String pspCode;

    @Column(name = "psp_token", nullable = false, updatable = false)
    private String pspToken;

    @Column(name = "psp_reference")
    private String pspReference;

    /** Ciphertext only (IV || data || tag). Never plaintext. */
    @Column(name = "encrypted_payload", nullable = false, updatable = false)
    private byte[] encryptedPayload;

    @Column(name = "payload_key_id", nullable = false, updatable = false)
    private String payloadKeyId;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Wrapper type on purpose: null => Spring Data treats the entity as new and issues an INSERT. */
    @Version
    private Long version;

    protected PaymentTransactionEntity() {
    }

    // Package-private accessors: only the adapter maps this class.
    UUID getId() { return id; }
    void setId(UUID id) { this.id = id; }
    String getMerchantId() { return merchantId; }
    void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    String getIdempotencyKey() { return idempotencyKey; }
    void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    String getRequestFingerprint() { return requestFingerprint; }
    void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    String getStatus() { return status; }
    void setStatus(String status) { this.status = status; }
    long getAmountMinor() { return amountMinor; }
    void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }
    String getCurrency() { return currency; }
    void setCurrency(String currency) { this.currency = currency; }
    long getCapturedMinor() { return capturedMinor; }
    void setCapturedMinor(long capturedMinor) { this.capturedMinor = capturedMinor; }
    long getRefundedMinor() { return refundedMinor; }
    void setRefundedMinor(long refundedMinor) { this.refundedMinor = refundedMinor; }
    String getPspCode() { return pspCode; }
    void setPspCode(String pspCode) { this.pspCode = pspCode; }
    String getPspToken() { return pspToken; }
    void setPspToken(String pspToken) { this.pspToken = pspToken; }
    String getPspReference() { return pspReference; }
    void setPspReference(String pspReference) { this.pspReference = pspReference; }
    byte[] getEncryptedPayload() { return encryptedPayload; }
    void setEncryptedPayload(byte[] encryptedPayload) { this.encryptedPayload = encryptedPayload; }
    String getPayloadKeyId() { return payloadKeyId; }
    void setPayloadKeyId(String payloadKeyId) { this.payloadKeyId = payloadKeyId; }
    String getFailureCode() { return failureCode; }
    void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    Instant getCreatedAt() { return createdAt; }
    void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    Instant getUpdatedAt() { return updatedAt; }
    void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    Long getVersion() { return version; }
}
