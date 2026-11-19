package com.alexis.payment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface PspContractJpaRepository extends JpaRepository<PspContractEntity, UUID> {

    Optional<PspContractEntity> findFirstByMerchantIdAndActiveTrueOrderByPriorityAsc(String merchantId);

    Optional<PspContractEntity> findByMerchantIdAndPspCode(String merchantId, String pspCode);
}
