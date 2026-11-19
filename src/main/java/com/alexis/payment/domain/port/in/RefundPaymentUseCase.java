package com.alexis.payment.domain.port.in;

import com.alexis.payment.domain.model.IdempotencyKey;
import com.alexis.payment.domain.model.MerchantId;
import com.alexis.payment.domain.model.PaymentId;
import com.alexis.payment.domain.model.PaymentTransaction;

/** MERCHANT_BO channel. Partial refunds allowed; each refund is idempotent by its own key. */
public interface RefundPaymentUseCase {

    RefundResult refund(RefundPaymentCommand command);

    record RefundPaymentCommand(MerchantId merchantId, PaymentId paymentId, long amountMinor,
                                IdempotencyKey idempotencyKey) {
    }

    record RefundResult(PaymentTransaction payment, boolean replayed) {
    }
}
