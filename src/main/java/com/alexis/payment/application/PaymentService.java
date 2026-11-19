package com.alexis.payment.application;

import com.alexis.payment.domain.exception.DuplicateIdempotencyKeyException;
import com.alexis.payment.domain.exception.IdempotencyConflictException;
import com.alexis.payment.domain.exception.NoPspContractException;
import com.alexis.payment.domain.exception.PaymentNotFoundException;
import com.alexis.payment.domain.exception.PspDeclinedException;
import com.alexis.payment.domain.exception.PspUnavailableException;
import com.alexis.payment.domain.model.Money;
import com.alexis.payment.domain.model.OperationType;
import com.alexis.payment.domain.model.PaymentId;
import com.alexis.payment.domain.model.PaymentStatus;
import com.alexis.payment.domain.model.PaymentTransaction;
import com.alexis.payment.domain.model.PspContract;
import com.alexis.payment.domain.model.MerchantId;
import com.alexis.payment.domain.port.in.AuthorizePaymentUseCase;
import com.alexis.payment.domain.port.in.CapturePaymentUseCase;
import com.alexis.payment.domain.port.in.GetPaymentQuery;
import com.alexis.payment.domain.port.in.RefundPaymentUseCase;
import com.alexis.payment.domain.port.in.VoidPaymentUseCase;
import com.alexis.payment.domain.port.out.PaymentRepositoryPort;
import com.alexis.payment.domain.port.out.PspAdapterPort;
import com.alexis.payment.domain.port.out.PspAdapterPort.AuthorizationRequest;
import com.alexis.payment.domain.port.out.PspAdapterPort.PspResponse;
import com.alexis.payment.domain.port.out.PspContractPort;

import java.time.Clock;

/**
 * Orchestrates use cases. Framework-free on purpose (wired in {@code config}), so it is unit-testable with fakes.
 *
 * <p>Pattern for every PSP call: short DB transaction -> PSP call (no transaction, no lock held) -> short DB
 * transaction guarded by optimistic locking. See docs/adr/0004.
 */
public class PaymentService implements AuthorizePaymentUseCase, CapturePaymentUseCase, VoidPaymentUseCase,
        RefundPaymentUseCase, GetPaymentQuery {

    private final PaymentRepositoryPort payments;
    private final PspContractPort contracts;
    private final PspAdapterPort psp;
    private final Clock clock;

    public PaymentService(PaymentRepositoryPort payments, PspContractPort contracts, PspAdapterPort psp, Clock clock) {
        this.payments = payments;
        this.contracts = contracts;
        this.psp = psp;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ authorize (ECOMMERCE)

    @Override
    public AuthorizationResult authorize(AuthorizePaymentCommand command) {
        String fingerprint = RequestFingerprint.of(command);

        var existing = payments.findByIdempotencyKey(command.merchantId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        PspContract contract = contracts.findActiveContract(command.merchantId())
                .orElseThrow(() -> new NoPspContractException(command.merchantId()));

        PaymentTransaction payment = PaymentTransaction.initiate(PaymentId.newId(), command.merchantId(),
                command.idempotencyKey(), fingerprint, command.amount(), contract.pspCode(), command.pspToken(),
                command.encryptedPayload(), clock.instant());
        try {
            // Committed BEFORE calling the PSP: if we crash mid-call, the INITIATED row is the evidence to reconcile.
            payment = payments.insert(payment);
        } catch (DuplicateIdempotencyKeyException raceLost) {
            // A concurrent request with the same key inserted first: behave as a replay of it.
            PaymentTransaction winner = payments.findByIdempotencyKey(command.merchantId(), command.idempotencyKey())
                    .orElseThrow(() -> raceLost);
            return replay(winner, fingerprint);
        }
        return new AuthorizationResult(callPspAuthorize(payment, contract), false);
    }

    private AuthorizationResult replay(PaymentTransaction existing, String fingerprint) {
        if (!existing.requestFingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(existing.idempotencyKey().value());
        }
        if (existing.status() == PaymentStatus.INITIATED) {
            // Previous attempt ended with an unknown PSP outcome: retry. Same PSP idempotency key => no double charge.
            PspContract contract = contractOf(existing);
            return new AuthorizationResult(callPspAuthorize(existing, contract), true);
        }
        return new AuthorizationResult(existing, true);
    }

    private PaymentTransaction callPspAuthorize(PaymentTransaction payment, PspContract contract) {
        PspResponse response = psp.authorize(new AuthorizationRequest(contract, payment.id(), payment.merchantId(),
                payment.idempotencyKey(), payment.amount(), payment.pspToken(), payment.encryptedPayload()));
        switch (response) {
            case PspResponse.Approved approved -> payment.markAuthorized(approved.reference(), clock.instant());
            case PspResponse.Declined declined -> payment.markAuthorizationDeclined(declined.reasonCode(), clock.instant());
            case PspResponse.Unavailable ignored ->
                    throw new PspUnavailableException(payment.id(), OperationType.AUTHORIZATION);
        }
        return payments.update(payment);
    }

    // ------------------------------------------------------------------ capture / void / refund (MERCHANT_BO)

    @Override
    public PaymentTransaction capture(CapturePaymentCommand command) {
        PaymentTransaction payment = load(command.merchantId(), command.paymentId());
        Money requested = command.amountMinor().isPresent()
                ? new Money(command.amountMinor().getAsLong(), payment.amount().currency())
                : payment.amount();

        if (payment.status() == PaymentStatus.CAPTURED && payment.capturedAmount().equals(requested)) {
            return payment; // idempotent replay of a successful capture
        }
        payment.assertCapturable(requested);

        PspResponse response = psp.capture(contractOf(payment), payment.pspReference(), requested,
                payment.id() + ":capture");
        switch (response) {
            case PspResponse.Approved approved -> payment.capture(requested, approved.reference(), clock.instant());
            case PspResponse.Declined declined -> declined(payment, OperationType.CAPTURE, requested, declined, null);
            case PspResponse.Unavailable ignored -> throw new PspUnavailableException(payment.id(), OperationType.CAPTURE);
        }
        return payments.update(payment);
    }

    @Override
    public PaymentTransaction voidPayment(VoidPaymentCommand command) {
        PaymentTransaction payment = load(command.merchantId(), command.paymentId());
        if (payment.status() == PaymentStatus.VOIDED) {
            return payment;
        }
        payment.assertVoidable();

        PspResponse response = psp.voidAuthorization(contractOf(payment), payment.pspReference(),
                payment.id() + ":void");
        switch (response) {
            case PspResponse.Approved approved -> payment.voidAuthorization(approved.reference(), clock.instant());
            case PspResponse.Declined declined -> declined(payment, OperationType.VOID, payment.amount(), declined, null);
            case PspResponse.Unavailable ignored -> throw new PspUnavailableException(payment.id(), OperationType.VOID);
        }
        return payments.update(payment);
    }

    @Override
    public RefundResult refund(RefundPaymentCommand command) {
        PaymentTransaction payment = load(command.merchantId(), command.paymentId());
        Money requested = new Money(command.amountMinor(), payment.amount().currency());

        var previous = payments.findOperation(payment.id(), command.idempotencyKey());
        if (previous.isPresent()) {
            if (!previous.get().amount().equals(requested)) {
                throw new IdempotencyConflictException(command.idempotencyKey().value());
            }
            if (!previous.get().succeeded()) {
                throw new PspDeclinedException(OperationType.REFUND, previous.get().failureCode());
            }
            return new RefundResult(payment, true);
        }
        payment.assertRefundable(requested);

        PspResponse response = psp.refund(contractOf(payment), payment.pspReference(), requested,
                payment.id() + ":refund:" + command.idempotencyKey().value());
        switch (response) {
            case PspResponse.Approved approved ->
                    payment.refund(requested, approved.reference(), command.idempotencyKey(), clock.instant());
            case PspResponse.Declined declined ->
                    declined(payment, OperationType.REFUND, requested, declined, command);
            case PspResponse.Unavailable ignored -> throw new PspUnavailableException(payment.id(), OperationType.REFUND);
        }
        return new RefundResult(payments.update(payment), false);
    }

    @Override
    public PaymentTransaction get(MerchantId merchantId, PaymentId paymentId) {
        return load(merchantId, paymentId);
    }

    // ------------------------------------------------------------------ helpers

    /** Journals the refusal, then surfaces it. The payment state itself is unchanged. */
    private void declined(PaymentTransaction payment, OperationType type, Money amount,
                          PspResponse.Declined declined, RefundPaymentCommand refund) {
        payment.recordDeclinedOperation(type, amount, declined.reasonCode(),
                refund == null ? null : refund.idempotencyKey(), clock.instant());
        payments.update(payment);
        throw new PspDeclinedException(type, declined.reasonCode());
    }

    private PaymentTransaction load(MerchantId merchantId, PaymentId paymentId) {
        return payments.findById(merchantId, paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    private PspContract contractOf(PaymentTransaction payment) {
        return contracts.findContract(payment.merchantId(), payment.pspCode())
                .orElseThrow(() -> new NoPspContractException(payment.merchantId()));
    }
}
