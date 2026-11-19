package com.alexis.payment.adapter.out.persistence;

import com.alexis.payment.domain.exception.ConcurrentPaymentUpdateException;
import com.alexis.payment.domain.exception.DuplicateIdempotencyKeyException;
import com.alexis.payment.domain.model.EncryptedPayload;
import com.alexis.payment.domain.model.IdempotencyKey;
import com.alexis.payment.domain.model.MerchantId;
import com.alexis.payment.domain.model.Money;
import com.alexis.payment.domain.model.OperationType;
import com.alexis.payment.domain.model.PaymentId;
import com.alexis.payment.domain.model.PaymentOperation;
import com.alexis.payment.domain.model.PaymentStatus;
import com.alexis.payment.domain.model.PaymentTransaction;
import com.alexis.payment.domain.model.PspContract;
import com.alexis.payment.domain.model.PspReference;
import com.alexis.payment.domain.model.PspToken;
import com.alexis.payment.domain.port.out.PaymentRepositoryPort;
import com.alexis.payment.domain.port.out.PspContractPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;

/**
 * Implements the persistence ports. Uses a {@link TransactionTemplate} rather than {@code @Transactional} so that
 * constraint violations are translated to domain exceptions OUTSIDE the transaction boundary (a violation inside a
 * {@code @Transactional} method would mark it rollback-only).
 */
@Component
class PaymentPersistenceAdapter implements PaymentRepositoryPort, PspContractPort {

    static final String UQ_IDEMPOTENCY = "uq_payment_merchant_idempotency";

    private final PaymentTransactionJpaRepository payments;
    private final PaymentOperationJpaRepository operations;
    private final PspContractJpaRepository contracts;
    private final TransactionTemplate tx;

    PaymentPersistenceAdapter(PaymentTransactionJpaRepository payments, PaymentOperationJpaRepository operations,
                              PspContractJpaRepository contracts, TransactionTemplate tx) {
        this.payments = payments;
        this.operations = operations;
        this.contracts = contracts;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ PaymentRepositoryPort

    @Override
    public Optional<PaymentTransaction> findByIdempotencyKey(MerchantId merchantId, IdempotencyKey key) {
        return payments.findByMerchantIdAndIdempotencyKey(merchantId.value(), key.value()).map(this::toDomain);
    }

    @Override
    public Optional<PaymentTransaction> findById(MerchantId merchantId, PaymentId id) {
        return payments.findByIdAndMerchantId(id.value(), merchantId.value()).map(this::toDomain);
    }

    @Override
    public Optional<PaymentOperation> findOperation(PaymentId paymentId, IdempotencyKey operationKey) {
        return operations.findByPaymentIdAndIdempotencyKey(paymentId.value(), operationKey.value())
                .map(PaymentPersistenceAdapter::toDomain);
    }

    @Override
    public PaymentTransaction insert(PaymentTransaction payment) {
        try {
            return tx.execute(status -> {
                PaymentTransactionEntity entity = new PaymentTransactionEntity();
                entity.setId(payment.id().value());
                entity.setMerchantId(payment.merchantId().value());
                entity.setIdempotencyKey(payment.idempotencyKey().value());
                entity.setRequestFingerprint(payment.requestFingerprint());
                entity.setAmountMinor(payment.amount().amountMinor());
                entity.setCurrency(payment.amount().currency().getCurrencyCode());
                entity.setPspCode(payment.pspCode());
                entity.setPspToken(payment.pspToken().value());
                entity.setEncryptedPayload(payment.encryptedPayload().ciphertext());
                entity.setPayloadKeyId(payment.encryptedPayload().keyId());
                entity.setCreatedAt(payment.createdAt());
                copyMutableState(payment, entity);
                PaymentTransactionEntity saved = payments.saveAndFlush(entity);
                appendOperations(payment);
                return toDomain(saved);
            });
        } catch (DataIntegrityViolationException e) {
            if (isConstraint(e, UQ_IDEMPOTENCY)) {
                throw new DuplicateIdempotencyKeyException(payment.idempotencyKey().value());
            }
            throw e;
        }
    }

    @Override
    public PaymentTransaction update(PaymentTransaction payment) {
        try {
            return tx.execute(status -> {
                PaymentTransactionEntity entity = payments.findById(payment.id().value())
                        .orElseThrow(() -> new IllegalStateException("payment vanished: " + payment.id()));
                // Explicit check: the aggregate was loaded in a previous transaction (before the PSP call).
                if (!Objects.equals(entity.getVersion(), payment.version())) {
                    throw new ConcurrentPaymentUpdateException(payment.id());
                }
                copyMutableState(payment, entity);
                // @Version still guards the window between this read and the flush.
                PaymentTransactionEntity saved = payments.saveAndFlush(entity);
                appendOperations(payment);
                return toDomain(saved);
            });
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
            // Lost race on version, or on the (payment_id, idempotency_key) unique index of a refund operation.
            throw new ConcurrentPaymentUpdateException(payment.id());
        }
    }

    // ------------------------------------------------------------------ PspContractPort

    @Override
    public Optional<PspContract> findActiveContract(MerchantId merchantId) {
        return contracts.findFirstByMerchantIdAndActiveTrueOrderByPriorityAsc(merchantId.value())
                .map(PaymentPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<PspContract> findContract(MerchantId merchantId, String pspCode) {
        return contracts.findByMerchantIdAndPspCode(merchantId.value(), pspCode)
                .map(PaymentPersistenceAdapter::toDomain);
    }

    // ------------------------------------------------------------------ mapping

    private void appendOperations(PaymentTransaction payment) {
        for (PaymentOperation op : payment.pullPendingOperations()) {
            operations.save(new PaymentOperationEntity(payment.id().value(), op.type().name(),
                    op.amount().amountMinor(), op.amount().currency().getCurrencyCode(), op.succeeded(),
                    op.pspReference() == null ? null : op.pspReference().value(),
                    op.idempotencyKey() == null ? null : op.idempotencyKey().value(),
                    op.failureCode(), op.occurredAt()));
        }
        operations.flush();
    }

    private static void copyMutableState(PaymentTransaction payment, PaymentTransactionEntity entity) {
        entity.setStatus(payment.status().name());
        entity.setCapturedMinor(payment.capturedAmount().amountMinor());
        entity.setRefundedMinor(payment.refundedAmount().amountMinor());
        entity.setPspReference(payment.pspReference() == null ? null : payment.pspReference().value());
        entity.setFailureCode(payment.failureCode());
        entity.setUpdatedAt(payment.updatedAt());
    }

    private PaymentTransaction toDomain(PaymentTransactionEntity e) {
        return PaymentTransaction.rehydrate(
                new PaymentId(e.getId()),
                new MerchantId(e.getMerchantId()),
                new IdempotencyKey(e.getIdempotencyKey()),
                e.getRequestFingerprint(),
                Money.of(e.getAmountMinor(), e.getCurrency()),
                e.getPspCode(),
                new PspToken(e.getPspToken()),
                new EncryptedPayload(e.getEncryptedPayload(), e.getPayloadKeyId()),
                PaymentStatus.valueOf(e.getStatus()),
                Money.of(e.getCapturedMinor(), e.getCurrency()),
                Money.of(e.getRefundedMinor(), e.getCurrency()),
                e.getPspReference() == null ? null : new PspReference(e.getPspReference()),
                e.getFailureCode(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getVersion());
    }

    private static PaymentOperation toDomain(PaymentOperationEntity e) {
        return new PaymentOperation(
                OperationType.valueOf(e.getType()),
                Money.of(e.getAmountMinor(), e.getCurrency()),
                e.isSucceeded(),
                e.getPspReference() == null ? null : new PspReference(e.getPspReference()),
                e.getIdempotencyKey() == null ? null : new IdempotencyKey(e.getIdempotencyKey()),
                e.getFailureCode(),
                e.getOccurredAt());
    }

    private static PspContract toDomain(PspContractEntity e) {
        return new PspContract(new MerchantId(e.getMerchantId()), e.getPspCode(), e.getMerchantAccountRef());
    }

    private static boolean isConstraint(DataIntegrityViolationException e, String constraintName) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains(constraintName);
    }
}
