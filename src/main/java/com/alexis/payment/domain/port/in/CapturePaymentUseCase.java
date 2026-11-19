package com.alexis.payment.domain.port.in;

import com.alexis.payment.domain.model.MerchantId;
import com.alexis.payment.domain.model.PaymentId;
import com.alexis.payment.domain.model.PaymentTransaction;

import java.util.OptionalLong;

/** MERCHANT_BO channel. Idempotent by state: capturing an already captured payment with the same amount is a no-op. */
public interface CapturePaymentUseCase {

    PaymentTransaction capture(CapturePaymentCommand command);

    /** @param amountMinor empty = capture the full authorized amount */
    record CapturePaymentCommand(MerchantId merchantId, PaymentId paymentId, OptionalLong amountMinor) {
    }
}
