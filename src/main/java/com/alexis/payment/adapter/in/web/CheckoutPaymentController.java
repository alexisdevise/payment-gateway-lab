package com.alexis.payment.adapter.in.web;

import com.alexis.payment.adapter.in.web.PaymentDtos.AuthorizePaymentRequest;
import com.alexis.payment.adapter.in.web.PaymentDtos.PaymentResponse;
import com.alexis.payment.adapter.in.web.security.ConsumerPrincipal;
import com.alexis.payment.domain.model.EncryptedPayload;
import com.alexis.payment.domain.model.IdempotencyKey;
import com.alexis.payment.domain.model.Money;
import com.alexis.payment.domain.model.PaymentId;
import com.alexis.payment.domain.model.PspToken;
import com.alexis.payment.domain.port.in.AuthorizePaymentUseCase;
import com.alexis.payment.domain.port.in.AuthorizePaymentUseCase.AuthorizePaymentCommand;
import com.alexis.payment.domain.port.in.AuthorizePaymentUseCase.AuthorizationResult;
import com.alexis.payment.domain.port.in.GetPaymentQuery;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Base64;
import java.util.UUID;

/** ECOMMERCE channel. */
@RestController
@RequestMapping("/api/v1/checkout/payments")
class CheckoutPaymentController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final AuthorizePaymentUseCase authorize;
    private final GetPaymentQuery query;

    CheckoutPaymentController(AuthorizePaymentUseCase authorize, GetPaymentQuery query) {
        this.authorize = authorize;
        this.query = query;
    }

    /**
     * 201 on first call, 200 + {@code Idempotent-Replayed: true} on replay. A PSP refusal is NOT an HTTP error:
     * the payment resource exists with status FAILED and a failure_code.
     */
    @PostMapping
    ResponseEntity<PaymentResponse> authorize(@RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                              @Valid @RequestBody AuthorizePaymentRequest body,
                                              @RequestAttribute(ConsumerPrincipal.REQUEST_ATTRIBUTE) ConsumerPrincipal consumer) {
        AuthorizationResult result = authorize.authorize(new AuthorizePaymentCommand(
                consumer.merchantId(),
                new IdempotencyKey(idempotencyKey),
                Money.of(body.amountMinor(), body.currency()),
                new PspToken(body.pspToken()),
                new EncryptedPayload(Base64.getDecoder().decode(body.encryptedConsumerPayload()), body.payloadKeyId())));

        PaymentResponse response = PaymentResponse.from(result.payment());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(URI.create("/api/v1/checkout/payments/" + response.paymentId()))
                .header(REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(response);
    }

    @GetMapping("/{paymentId}")
    PaymentResponse get(@PathVariable UUID paymentId,
                        @RequestAttribute(ConsumerPrincipal.REQUEST_ATTRIBUTE) ConsumerPrincipal consumer) {
        return PaymentResponse.from(query.get(consumer.merchantId(), new PaymentId(paymentId)));
    }
}
