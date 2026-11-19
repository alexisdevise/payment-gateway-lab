package com.alexis.payment.domain.port.out;

import com.alexis.payment.domain.model.MerchantId;
import com.alexis.payment.domain.model.PspContract;

import java.util.Optional;

public interface PspContractPort {

    /** Contract used to route a new authorization (highest priority active contract). */
    Optional<PspContract> findActiveContract(MerchantId merchantId);

    /** Contract a payment was authorized with: follow-ups must go to the same PSP. */
    Optional<PspContract> findContract(MerchantId merchantId, String pspCode);
}
