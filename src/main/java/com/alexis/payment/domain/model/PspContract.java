package com.alexis.payment.domain.model;

import java.util.Objects;

/**
 * Commercial contract between a merchant and a PSP. Owned by the payment API: consumers never choose the PSP.
 *
 * @param merchantAccountRef account identifier at the PSP. Credentials live in secrets (K8s Secret / vault), not in DB.
 */
public record PspContract(MerchantId merchantId, String pspCode, String merchantAccountRef) {
    public PspContract {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(pspCode, "pspCode");
        Objects.requireNonNull(merchantAccountRef, "merchantAccountRef");
    }
}
