package com.alexis.payment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface PaymentTransactionJpaRepository extends JpaRepository<PaymentTransactionEntity, UUID> {

    Optional<PaymentTransactionEntity> findByMerchantIdAndIdempotencyKey(String merchantId, String idempotencyKey);

    Optional<PaymentTransactionEntity> findByIdAndMerchantId(UUID id, String merchantId);
}
