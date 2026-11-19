package com.alexis.payment.adapter.in.web;

import com.alexis.payment.adapter.in.web.PaymentDtos.CaptureRequest;
import com.alexis.payment.adapter.in.web.PaymentDtos.PaymentResponse;
import com.alexis.payment.adapter.in.web.PaymentDtos.RefundRequest;
import com.alexis.payment.adapter.in.web.security.ConsumerPrincipal;
import com.alexis.payment.domain.model.IdempotencyKey;
import com.alexis.payment.domain.model.PaymentId;
import com.alexis.payment.domain.port.in.CapturePaymentUseCase;
import com.alexis.payment.domain.port.in.CapturePaymentUseCase.CapturePaymentCommand;
import com.alexis.payment.domain.port.in.GetPaymentQuery;
import com.alexis.payment.domain.port.in.RefundPaymentUseCase;
import com.alexis.payment.domain.port.in.RefundPaymentUseCase.RefundPaymentCommand;
import com.alexis.payment.domain.port.in.RefundPaymentUseCase.RefundResult;
import com.alexis.payment.domain.port.in.VoidPaymentUseCase;
import com.alexis.payment.domain.port.in.VoidPaymentUseCase.VoidPaymentCommand;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.OptionalLong;
import java.util.UUID;

/** MERCHANT_BO channel. */
@RestController
@RequestMapping("/api/v1/merchant/payments")
class MerchantPaymentController {

    private final CapturePaymentUseCase capture;
    private final VoidPaymentUseCase voidPayment;
    private final RefundPaymentUseCase refund;
    private final GetPaymentQuery query;

    MerchantPaymentController(CapturePaymentUseCase capture, VoidPaymentUseCase voidPayment,
                              RefundPaymentUseCase refund, GetPaymentQuery query) {
        this.capture = capture;
        this.voidPayment = voidPayment;
        this.refund = refund;
        this.query = query;
    }

    @PostMapping("/{paymentId}/capture")
    PaymentResponse capture(@PathVariable UUID paymentId,
                            @Valid @RequestBody(required = false) CaptureRequest body,
                            @RequestAttribute(ConsumerPrincipal.REQUEST_ATTRIBUTE) ConsumerPrincipal consumer) {
        OptionalLong amount = body == null || body.amountMinor() == null
                ? OptionalLong.empty() : OptionalLong.of(body.amountMinor());
        return PaymentResponse.from(capture.capture(
                new CapturePaymentCommand(consumer.merchantId(), new PaymentId(paymentId), amount)));
    }

    @PostMapping("/{paymentId}/void")
    PaymentResponse voidPayment(@PathVariable UUID paymentId,
                                @RequestAttribute(ConsumerPrincipal.REQUEST_ATTRIBUTE) ConsumerPrincipal consumer) {
        return PaymentResponse.from(voidPayment.voidPayment(
                new VoidPaymentCommand(consumer.merchantId(), new PaymentId(paymentId))));
    }

    @PostMapping("/{paymentId}/refund")
    ResponseEntity<PaymentResponse> refund(@PathVariable UUID paymentId,
                                           @RequestHeader(CheckoutPaymentController.IDEMPOTENCY_HEADER) String idempotencyKey,
                                           @Valid @RequestBody RefundRequest body,
                                           @RequestAttribute(ConsumerPrincipal.REQUEST_ATTRIBUTE) ConsumerPrincipal consumer) {
        RefundResult result = refund.refund(new RefundPaymentCommand(consumer.merchantId(), new PaymentId(paymentId),
                body.amountMinor(), new IdempotencyKey(idempotencyKey)));
        return ResponseEntity.ok()
                .header(CheckoutPaymentController.REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(PaymentResponse.from(result.payment()));
    }

    @GetMapping("/{paymentId}")
    PaymentResponse get(@PathVariable UUID paymentId,
                        @RequestAttribute(ConsumerPrincipal.REQUEST_ATTRIBUTE) ConsumerPrincipal consumer) {
        return PaymentResponse.from(query.get(consumer.merchantId(), new PaymentId(paymentId)));
    }
}
