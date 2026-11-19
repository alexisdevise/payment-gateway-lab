package com.alexis.payment.domain.exception;

import com.alexis.payment.domain.model.MerchantId;

public class NoPspContractException extends DomainException {
    public NoPspContractException(MerchantId merchantId) {
        super("no_psp_contract", "No active PSP contract for merchant " + merchantId);
    }
}
