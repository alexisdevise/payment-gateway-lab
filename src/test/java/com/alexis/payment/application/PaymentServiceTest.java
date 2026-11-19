package com.alexis.payment.application;

import com.alexis.payment.domain.exception.ConcurrentPaymentUpdateException;
import com.alexis.payment.domain.exception.DuplicateIdempotencyKeyException;
import com.alexis.payment.domain.exception.IdempotencyConflictException;
import com.alexis.payment.domain.exception.PspDeclinedException;
import com.alexis.payment.domain.exception.PspUnavailableException;
import com.alexis.payment.domain.model.EncryptedPayload;
import com.alexis.payment.domain.model.IdempotencyKey;
import com.alexis.payment.domain.model.MerchantId;
import com.alexis.payment.domain.model.Money;
import com.alexis.payment.domain.model.PaymentId;
import com.alexis.payment.domain.model.PaymentOperation;
import com.alexis.payment.domain.model.PaymentStatus;
import com.alexis.payment.domain.model.PaymentTransaction;
import com.alexis.payment.domain.model.PspContract;
import com.alexis.payment.domain.model.PspReference;
import com.alexis.payment.domain.model.PspToken;
import com.alexis.payment.domain.port.in.AuthorizePaymentUseCase.AuthorizePaymentCommand;
import com.alexis.payment.domain.port.in.CapturePaymentUseCase.CapturePaymentCommand;
import com.alexis.payment.domain.port.in.RefundPaymentUseCase.RefundPaymentCommand;
import com.alexis.payment.domain.port.out.PaymentRepositoryPort;
import com.alexis.payment.domain.port.out.PspAdapterPort;
import com.alexis.payment.domain.port.out.PspContractPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests: the application core runs with hand-written fakes, no Spring, no DB. */
class PaymentServiceTest {

    private static final MerchantId MERCHANT = new MerchantId("merchant-demo");

    private final InMemoryPayments payments = new InMemoryPayments();
    private final ScriptedPsp psp = new ScriptedPsp();
    private final PspContractPort contracts = new PspContractPort() {
        private final PspContract contract = new PspContract(MERCHANT, "SANDBOX", "acct");

        @Override
        public Optional<PspContract> findActiveContract(MerchantId merchantId) {
            return Optional.of(contract).filter(c -> c.merchantId().equals(merchantId));
        }

        @Override
        public Optional<PspContract> findContract(MerchantId merchantId, String pspCode) {
            return findActiveContract(merchantId);
        }
    };
    private final PaymentService service = new PaymentService(payments, contracts, psp,
            Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void authorization_approved() {
        var result = service.authorize(command("order-0001", 25_00));

        assertThat(result.replayed()).isFalse();
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(psp.authorizeCalls).isEqualTo(1);
    }

    @Test
    void same_key_same_body_is_replayed_without_calling_the_psp_again() {
        service.authorize(command("order-0001", 25_00));
        var replay = service.authorize(command("order-0001", 25_00));

        assertThat(replay.replayed()).isTrue();
        assertThat(psp.authorizeCalls).isEqualTo(1);
    }

    @Test
    void same_key_different_body_is_rejected() {
        service.authorize(command("order-0001", 25_00));

        assertThatThrownBy(() -> service.authorize(command("order-0001", 99_00)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void psp_timeout_leaves_payment_initiated_and_retry_completes_it() {
        psp.next(new PspAdapterPort.PspResponse.Unavailable("timeout"));

        assertThatThrownBy(() -> service.authorize(command("order-0001", 25_00)))
                .isInstanceOf(PspUnavailableException.class);
        assertThat(payments.only().status()).isEqualTo(PaymentStatus.INITIATED);

        var retry = service.authorize(command("order-0001", 25_00));

        assertThat(retry.payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(psp.pspIdempotencyKeys).as("both attempts reuse the same PSP idempotency key")
                .hasSize(2).containsOnly(retry.payment().id() + ":authorize");
    }

    @Test
    void concurrent_insert_with_same_key_is_turned_into_a_replay() {
        service.authorize(command("order-0001", 25_00));
        payments.simulateLostInsertRace = true;

        var result = service.authorize(command("order-0001", 25_00));

        assertThat(result.replayed()).isTrue();
    }

    @Test
    void declined_capture_keeps_authorized_state_and_journals_the_attempt() {
        PaymentId id = service.authorize(command("order-0001", 25_00)).payment().id();
        psp.next(new PspAdapterPort.PspResponse.Declined("authorization_expired"));

        assertThatThrownBy(() -> service.capture(new CapturePaymentCommand(MERCHANT, id, OptionalLong.empty())))
                .isInstanceOf(PspDeclinedException.class);
        assertThat(payments.only().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payments.operations).extracting(PaymentOperation::succeeded).containsExactly(true, false);
    }

    @Test
    void capture_twice_with_same_amount_is_idempotent() {
        PaymentId id = service.authorize(command("order-0001", 25_00)).payment().id();
        service.capture(new CapturePaymentCommand(MERCHANT, id, OptionalLong.empty()));
        service.capture(new CapturePaymentCommand(MERCHANT, id, OptionalLong.empty()));

        assertThat(psp.captureCalls).isEqualTo(1);
    }

    @Test
    void refund_is_idempotent_per_key_and_rejects_key_reuse_with_other_amount() {
        PaymentId id = service.authorize(command("order-0001", 25_00)).payment().id();
        service.capture(new CapturePaymentCommand(MERCHANT, id, OptionalLong.empty()));
        IdempotencyKey refundKey = new IdempotencyKey("refund-0001");

        service.refund(new RefundPaymentCommand(MERCHANT, id, 5_00, refundKey));
        var replay = service.refund(new RefundPaymentCommand(MERCHANT, id, 5_00, refundKey));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.payment().refundedAmount().amountMinor()).isEqualTo(5_00);
        assertThatThrownBy(() -> service.refund(new RefundPaymentCommand(MERCHANT, id, 6_00, refundKey)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void stale_version_is_reported_as_concurrent_update() {
        PaymentId id = service.authorize(command("order-0001", 25_00)).payment().id();
        payments.bumpVersionBeforeNextUpdate = true;

        assertThatThrownBy(() -> service.capture(new CapturePaymentCommand(MERCHANT, id, OptionalLong.empty())))
                .isInstanceOf(ConcurrentPaymentUpdateException.class);
    }

    // ------------------------------------------------------------------ fixtures & fakes

    private static AuthorizePaymentCommand command(String key, long amount) {
        return new AuthorizePaymentCommand(MERCHANT, new IdempotencyKey(key), Money.of(amount, "EUR"),
                new PspToken("tok_visa_4242"), new EncryptedPayload(new byte[64], "k1"));
    }

    /** Behaves like the JPA adapter: stores immutable snapshots, checks versions, enforces the unique key. */
    static final class InMemoryPayments implements PaymentRepositoryPort {
        final Map<PaymentId, PaymentTransaction> rows = new HashMap<>();
        final List<PaymentOperation> operations = new ArrayList<>();
        boolean simulateLostInsertRace;
        boolean bumpVersionBeforeNextUpdate;

        @Override
        public Optional<PaymentTransaction> findByIdempotencyKey(MerchantId merchantId, IdempotencyKey key) {
            if (simulateLostInsertRace) {
                simulateLostInsertRace = false;
                return Optional.empty(); // first lookup misses, insert will then collide
            }
            return rows.values().stream()
                    .filter(p -> p.merchantId().equals(merchantId) && p.idempotencyKey().equals(key))
                    .findFirst().map(this::copy);
        }

        @Override
        public Optional<PaymentTransaction> findById(MerchantId merchantId, PaymentId id) {
            return Optional.ofNullable(rows.get(id)).filter(p -> p.merchantId().equals(merchantId)).map(this::copy);
        }

        @Override
        public Optional<PaymentOperation> findOperation(PaymentId paymentId, IdempotencyKey key) {
            return operations.stream().filter(o -> key.equals(o.idempotencyKey())).findFirst();
        }

        @Override
        public PaymentTransaction insert(PaymentTransaction p) {
            boolean duplicate = rows.values().stream()
                    .anyMatch(r -> r.merchantId().equals(p.merchantId()) && r.idempotencyKey().equals(p.idempotencyKey()));
            if (duplicate) {
                throw new DuplicateIdempotencyKeyException(p.idempotencyKey().value());
            }
            return store(p, 0);
        }

        @Override
        public PaymentTransaction update(PaymentTransaction p) {
            long stored = rows.get(p.id()).version() + (bumpVersionBeforeNextUpdate ? 1 : 0);
            bumpVersionBeforeNextUpdate = false;
            if (stored != p.version()) {
                throw new ConcurrentPaymentUpdateException(p.id());
            }
            return store(p, stored + 1);
        }

        PaymentTransaction only() {
            assertThat(rows).hasSize(1);
            return rows.values().iterator().next();
        }

        private PaymentTransaction store(PaymentTransaction p, long version) {
            operations.addAll(p.pullPendingOperations());
            PaymentTransaction snapshot = PaymentTransaction.rehydrate(p.id(), p.merchantId(), p.idempotencyKey(),
                    p.requestFingerprint(), p.amount(), p.pspCode(), p.pspToken(), p.encryptedPayload(), p.status(),
                    p.capturedAmount(), p.refundedAmount(), p.pspReference(), p.failureCode(), p.createdAt(),
                    p.updatedAt(), version);
            rows.put(p.id(), snapshot);
            return copy(snapshot);
        }

        private PaymentTransaction copy(PaymentTransaction p) {
            return PaymentTransaction.rehydrate(p.id(), p.merchantId(), p.idempotencyKey(), p.requestFingerprint(),
                    p.amount(), p.pspCode(), p.pspToken(), p.encryptedPayload(), p.status(), p.capturedAmount(),
                    p.refundedAmount(), p.pspReference(), p.failureCode(), p.createdAt(), p.updatedAt(), p.version());
        }
    }

    /** Approves by default; individual responses can be scripted. */
    static final class ScriptedPsp implements PspAdapterPort {
        final Queue<PspResponse> scripted = new LinkedList<>();
        final List<String> pspIdempotencyKeys = new ArrayList<>();
        int authorizeCalls;
        int captureCalls;
        private int sequence;

        void next(PspResponse response) {
            scripted.add(response);
        }

        @Override
        public PspResponse authorize(AuthorizationRequest request) {
            authorizeCalls++;
            pspIdempotencyKeys.add(request.pspIdempotencyKey());
            return respond();
        }

        @Override
        public PspResponse capture(PspContract c, PspReference a, Money m, String key) {
            captureCalls++;
            return respond();
        }

        @Override
        public PspResponse voidAuthorization(PspContract c, PspReference a, String key) {
            return respond();
        }

        @Override
        public PspResponse refund(PspContract c, PspReference a, Money m, String key) {
            return respond();
        }

        private PspResponse respond() {
            PspResponse next = scripted.poll();
            return next != null ? next : new PspResponse.Approved(new PspReference("psp_" + (++sequence)));
        }
    }
}
