package com.alexis.payment.domain.model;

import com.alexis.payment.domain.exception.AmountExceededException;
import com.alexis.payment.domain.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTransactionTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final PspReference REF = new PspReference("psp_ref_1");

    @Test
    void new_payment_is_initiated_with_nothing_captured_or_refunded() {
        PaymentTransaction p = newPayment(10_00);

        assertThat(p.status()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(p.capturedAmount().amountMinor()).isZero();
        assertThat(p.refundedAmount().amountMinor()).isZero();
    }

    @Test
    void zero_amount_is_rejected() {
        assertThatThrownBy(() -> newPayment(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void happy_path_authorize_capture_full_refund() {
        PaymentTransaction p = newPayment(10_00);

        p.markAuthorized(REF, NOW);
        p.capture(eur(10_00), new PspReference("cap_1"), NOW);
        p.refund(eur(10_00), new PspReference("ref_1"), key("refund-0001"), NOW);

        assertThat(p.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(p.pullPendingOperations()).extracting(PaymentOperation::type)
                .containsExactly(OperationType.AUTHORIZATION, OperationType.CAPTURE, OperationType.REFUND);
        assertThat(p.pullPendingOperations()).as("operations are drained once pulled").isEmpty();
    }

    @Test
    void partial_refunds_keep_payment_captured_until_fully_refunded() {
        PaymentTransaction p = capturedPayment(10_00);

        p.refund(eur(3_00), new PspReference("r1"), key("refund-0001"), NOW);
        assertThat(p.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(p.remainingRefundable().amountMinor()).isEqualTo(7_00);

        p.refund(eur(7_00), new PspReference("r2"), key("refund-0002"), NOW);
        assertThat(p.status()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void cannot_refund_more_than_captured() {
        PaymentTransaction p = capturedPayment(10_00);
        p.refund(eur(6_00), new PspReference("r1"), key("refund-0001"), NOW);

        assertThatThrownBy(() -> p.assertRefundable(eur(5_00)))
                .isInstanceOf(AmountExceededException.class);
    }

    @Test
    void partial_capture_limits_what_can_be_refunded() {
        PaymentTransaction p = newPayment(10_00);
        p.markAuthorized(REF, NOW);
        p.capture(eur(4_00), new PspReference("cap"), NOW);

        assertThatThrownBy(() -> p.assertRefundable(eur(5_00))).isInstanceOf(AmountExceededException.class);
    }

    @Test
    void cannot_capture_more_than_authorized() {
        PaymentTransaction p = newPayment(10_00);
        p.markAuthorized(REF, NOW);

        assertThatThrownBy(() -> p.assertCapturable(eur(10_01))).isInstanceOf(AmountExceededException.class);
    }

    @Test
    void cannot_capture_in_another_currency() {
        PaymentTransaction p = newPayment(10_00);
        p.markAuthorized(REF, NOW);

        assertThatThrownBy(() -> p.assertCapturable(Money.of(10_00, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cannot_capture_before_authorization() {
        PaymentTransaction p = newPayment(10_00);

        assertThatThrownBy(() -> p.assertCapturable(eur(10_00))).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void cannot_void_a_captured_payment() {
        PaymentTransaction p = capturedPayment(10_00);

        assertThatThrownBy(p::assertVoidable).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void declined_authorization_is_terminal() {
        PaymentTransaction p = newPayment(10_00);
        p.markAuthorizationDeclined("card_declined", NOW);

        assertThat(p.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.failureCode()).isEqualTo("card_declined");
        assertThatThrownBy(() -> p.markAuthorized(REF, NOW)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void declined_follow_up_is_journaled_without_changing_state() {
        PaymentTransaction p = newPayment(10_00);
        p.markAuthorized(REF, NOW);
        p.pullPendingOperations();

        p.recordDeclinedOperation(OperationType.CAPTURE, eur(10_00), "expired_authorization", null, NOW);

        assertThat(p.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(p.pullPendingOperations()).singleElement()
                .satisfies(op -> assertThat(op.succeeded()).isFalse());
    }

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111111", "4111 1111 1111 1111", "4111-1111-1111-1111"})
    void psp_token_rejects_anything_that_looks_like_a_card_number(String pan) {
        assertThatThrownBy(() -> new PspToken(pan)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void to_string_never_exposes_token_or_payload() {
        PaymentTransaction p = newPayment(10_00);

        assertThat(p.toString()).doesNotContain("tok_visa_secret");
        assertThat(p.pspToken().toString()).doesNotContain("visa_secret");
    }

    // ------------------------------------------------------------------ fixtures

    private static PaymentTransaction capturedPayment(long amount) {
        PaymentTransaction p = newPayment(amount);
        p.markAuthorized(REF, NOW);
        p.capture(eur(amount), new PspReference("cap"), NOW);
        return p;
    }

    static PaymentTransaction newPayment(long amountMinor) {
        return PaymentTransaction.initiate(PaymentId.newId(), new MerchantId("merchant-demo"), key("checkout-0001"),
                "fingerprint", eur(amountMinor), "SANDBOX", new PspToken("tok_visa_secret"),
                new EncryptedPayload(new byte[40], "k1"), NOW);
    }

    private static Money eur(long amountMinor) {
        return Money.of(amountMinor, "EUR");
    }

    private static IdempotencyKey key(String value) {
        return new IdempotencyKey(value);
    }
}
