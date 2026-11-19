package com.alexis.payment.domain.port.in;

import com.alexis.payment.domain.model.EncryptedPayload;
import com.alexis.payment.domain.model.IdempotencyKey;
import com.alexis.payment.domain.model.MerchantId;
import com.alexis.payment.domain.model.Money;
import com.alexis.payment.domain.model.PaymentTransaction;
import com.alexis.payment.domain.model.PspToken;

/** ECOMMERCE channel: authorize a payment with a PSP token and an encrypted consumer payload. */
public interface AuthorizePaymentUseCase {

    AuthorizationResult authorize(AuthorizePaymentCommand command);

    /** merchantId comes from the authenticated consumer, never from the request body. */
    record AuthorizePaymentCommand(MerchantId merchantId, IdempotencyKey idempotencyKey, Money amount,
                                   PspToken pspToken, EncryptedPayload encryptedPayload) {
    }

    /** @param replayed true when the response comes from a previous request with the same Idempotency-Key */
    record AuthorizationResult(PaymentTransaction payment, boolean replayed) {
    }
}
