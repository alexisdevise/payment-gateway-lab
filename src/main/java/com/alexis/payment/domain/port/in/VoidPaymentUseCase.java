package com.alexis.payment.domain.port.in;

import com.alexis.payment.domain.model.MerchantId;
import com.alexis.payment.domain.model.PaymentId;
import com.alexis.payment.domain.model.PaymentTransaction;

/** MERCHANT_BO channel. Releases an authorization that will not be captured. Idempotent by state. */
public interface VoidPaymentUseCase {

    PaymentTransaction voidPayment(VoidPaymentCommand command);

    record VoidPaymentCommand(MerchantId merchantId, PaymentId paymentId) {
    }
}
