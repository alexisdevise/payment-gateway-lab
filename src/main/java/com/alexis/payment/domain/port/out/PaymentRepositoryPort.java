package com.alexis.payment.domain.port.out;

import com.alexis.payment.domain.exception.ConcurrentPaymentUpdateException;
import com.alexis.payment.domain.exception.DuplicateIdempotencyKeyException;
import com.alexis.payment.domain.model.IdempotencyKey;
import com.alexis.payment.domain.model.MerchantId;
import com.alexis.payment.domain.model.PaymentId;
import com.alexis.payment.domain.model.PaymentOperation;
import com.alexis.payment.domain.model.PaymentTransaction;

import java.util.Optional;

/** Each method is its own short transaction: no DB transaction is ever held open during a PSP call. */
public interface PaymentRepositoryPort {

    Optional<PaymentTransaction> findByIdempotencyKey(MerchantId merchantId, IdempotencyKey key);

    Optional<PaymentTransaction> findById(MerchantId merchantId, PaymentId id);

    Optional<PaymentOperation> findOperation(PaymentId paymentId, IdempotencyKey operationKey);

    /** @throws DuplicateIdempotencyKeyException if (merchant, idempotency key) already exists */
    PaymentTransaction insert(PaymentTransaction payment);

    /**
     * Saves state and appends pending operations atomically.
     *
     * @throws ConcurrentPaymentUpdateException if the stored version differs (optimistic lock)
     */
    PaymentTransaction update(PaymentTransaction payment);
}
