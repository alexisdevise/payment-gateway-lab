package com.alexis.payment.domain.port.in;

import com.alexis.payment.domain.model.MerchantId;
import com.alexis.payment.domain.model.PaymentId;
import com.alexis.payment.domain.model.PaymentTransaction;

public interface GetPaymentQuery {

    /** Scoped by merchant: a payment of another merchant is reported as not found. */
    PaymentTransaction get(MerchantId merchantId, PaymentId paymentId);
}
