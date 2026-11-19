package com.alexis.payment.adapter.out.psp.sandbox;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process simulation of a PSP, good enough to exercise every branch end to end:
 * <ul>
 *   <li>token {@code tok_sandbox_decline} -> refused</li>
 *   <li>token {@code tok_sandbox_unavailable} -> technical error (outcome unknown)</li>
 *   <li>anything else -> approved</li>
 * </ul>
 * Like a real PSP it de-duplicates on the idempotency key: the same key always returns the first result.
 */
public class SimulatedSandboxPspApi implements SandboxPspApi {

    public static final String DECLINE_TOKEN = "tok_sandbox_decline";
    public static final String UNAVAILABLE_TOKEN = "tok_sandbox_unavailable";

    private final Map<String, SandboxResult> responsesByIdempotencyKey = new ConcurrentHashMap<>();

    @Override
    public SandboxResult authorize(AuthorizeCall call) {
        if (UNAVAILABLE_TOKEN.equals(call.paymentMethodToken())) {
            return new SandboxResult("error", null, "upstream_timeout"); // not memoized: outcome unknown
        }
        return responsesByIdempotencyKey.computeIfAbsent(call.idempotencyKey(), k ->
                DECLINE_TOKEN.equals(call.paymentMethodToken())
                        ? new SandboxResult("refused", null, "card_declined")
                        : approved("auth"));
    }

    @Override
    public SandboxResult capture(String merchantAccount, String authorizationId, long amountMinor, String currency,
                                 String idempotencyKey) {
        return responsesByIdempotencyKey.computeIfAbsent(idempotencyKey, k -> approved("cap"));
    }

    @Override
    public SandboxResult cancel(String merchantAccount, String authorizationId, String idempotencyKey) {
        return responsesByIdempotencyKey.computeIfAbsent(idempotencyKey, k -> approved("cnl"));
    }

    @Override
    public SandboxResult refund(String merchantAccount, String authorizationId, long amountMinor, String currency,
                                String idempotencyKey) {
        return responsesByIdempotencyKey.computeIfAbsent(idempotencyKey, k -> approved("ref"));
    }

    private static SandboxResult approved(String prefix) {
        return new SandboxResult("approved", prefix + "_" + UUID.randomUUID(), null);
    }
}
