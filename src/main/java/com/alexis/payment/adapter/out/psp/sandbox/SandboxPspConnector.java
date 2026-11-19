package com.alexis.payment.adapter.out.psp.sandbox;

import com.alexis.payment.adapter.out.crypto.AesGcmPayloadCipher;
import com.alexis.payment.adapter.out.crypto.AesGcmPayloadCipher.PayloadDecryptionException;
import com.alexis.payment.adapter.out.psp.ConsumerPayload;
import com.alexis.payment.adapter.out.psp.PspConnector;
import com.alexis.payment.adapter.out.psp.sandbox.SandboxPspApi.AuthorizeCall;
import com.alexis.payment.adapter.out.psp.sandbox.SandboxPspApi.SandboxResult;
import com.alexis.payment.domain.model.Money;
import com.alexis.payment.domain.model.PspContract;
import com.alexis.payment.domain.model.PspReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;

/**
 * Anti-corruption layer for the "SANDBOX" PSP. This is the ONLY place where the consumer payload is decrypted:
 * decrypt -> map to the PSP contract -> call -> drop. Plaintext is never stored, never logged.
 */
public class SandboxPspConnector implements PspConnector {

    public static final String PSP_CODE = "SANDBOX";
    private static final Logger log = LoggerFactory.getLogger(SandboxPspConnector.class);

    private final AesGcmPayloadCipher cipher;
    private final ObjectMapper objectMapper;
    private final SandboxPspApi api;

    public SandboxPspConnector(AesGcmPayloadCipher cipher, ObjectMapper objectMapper, SandboxPspApi api) {
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.api = api;
    }

    @Override
    public String pspCode() {
        return PSP_CODE;
    }

    @Override
    public PspResponse authorize(AuthorizationRequest request) {
        ConsumerPayload consumer;
        try {
            consumer = decrypt(request);
        } catch (PayloadDecryptionException | IOException e) {
            // Logged without payload content, only the reason class.
            log.warn("Consumer payload rejected for payment {}: {}", request.paymentId(), e.getClass().getSimpleName());
            return new PspResponse.Declined("invalid_consumer_payload");
        }

        AuthorizeCall call = new AuthorizeCall(
                request.contract().merchantAccountRef(),
                request.amount().amountMinor(),
                request.amount().currency().getCurrencyCode(),
                request.pspToken().value(),
                consumer.customer() == null ? null : consumer.customer().email(),
                consumer.customer() == null ? null : consumer.customer().firstName() + " " + consumer.customer().lastName(),
                consumer.billingAddress() == null ? null : consumer.billingAddress().countryCode(),
                consumer.orderReference(),
                request.pspIdempotencyKey());
        return toResponse(api.authorize(call));
    }

    @Override
    public PspResponse capture(PspContract contract, PspReference authorization, Money amount, String key) {
        return toResponse(api.capture(contract.merchantAccountRef(), authorization.value(), amount.amountMinor(),
                amount.currency().getCurrencyCode(), key));
    }

    @Override
    public PspResponse voidAuthorization(PspContract contract, PspReference authorization, String key) {
        return toResponse(api.cancel(contract.merchantAccountRef(), authorization.value(), key));
    }

    @Override
    public PspResponse refund(PspContract contract, PspReference authorization, Money amount, String key) {
        return toResponse(api.refund(contract.merchantAccountRef(), authorization.value(), amount.amountMinor(),
                amount.currency().getCurrencyCode(), key));
    }

    private ConsumerPayload decrypt(AuthorizationRequest request) throws IOException {
        byte[] plaintext = cipher.decrypt(request.encryptedPayload(),
                AesGcmPayloadCipher.associatedData(request.merchantId(), request.idempotencyKey()));
        try {
            return objectMapper.readValue(plaintext, ConsumerPayload.class);
        } finally {
            Arrays.fill(plaintext, (byte) 0); // best effort: shorten the plaintext lifetime in heap
        }
    }

    private static PspResponse toResponse(SandboxResult result) {
        if (result == null) {
            return new PspResponse.Unavailable("empty_response");
        }
        return switch (result.status()) {
            case "approved" -> new PspResponse.Approved(new PspReference(result.pspReference()));
            case "refused" -> new PspResponse.Declined(result.refusalReason());
            default -> new PspResponse.Unavailable(result.refusalReason());
        };
    }
}
