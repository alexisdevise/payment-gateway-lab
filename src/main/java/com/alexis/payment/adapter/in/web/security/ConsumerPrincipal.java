package com.alexis.payment.adapter.in.web.security;

import com.alexis.payment.domain.model.MerchantId;

/** Authenticated caller. The merchant scope comes from here, never from the request body. */
public record ConsumerPrincipal(String consumerId, ConsumerChannel channel, MerchantId merchantId) {
    /** Must be a compile-time constant (used in @RequestAttribute), hence a literal and not getClass().getName(). */
    public static final String REQUEST_ATTRIBUTE = "payment.consumerPrincipal";
}
