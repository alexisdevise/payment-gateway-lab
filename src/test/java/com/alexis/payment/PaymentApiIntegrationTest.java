package com.alexis.payment;

import com.alexis.payment.adapter.out.crypto.AesGcmPayloadCipher;
import com.alexis.payment.adapter.out.psp.sandbox.SimulatedSandboxPspApi;
import com.alexis.payment.domain.model.IdempotencyKey;
import com.alexis.payment.domain.model.MerchantId;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end through HTTP, Spring, Flyway and a real PostgreSQL (Testcontainers).
 * Uses the "local" profile for consumers / demo PSP contracts. The test owns its secrets (no .env in CI):
 * the AES key and the SHA-256 of the API keys below; the datasource comes from Testcontainers via @ServiceConnection.
 */
@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "PAYMENT_CRYPTO_KEY_DEV=OR+gyWqOKMP+WybfmCTzFW1LYLAZ6u2d5zCW46iv4fI=",
        "API_KEY_SHA256_CHECKOUT_WEB=180571dc3b0a005f768df736f1f36be396b48b5827f9caf4bc8352f4efdec9c6",
        "API_KEY_SHA256_MERCHANT_BACKOFFICE=f42484cc93a1f4e1fff048dbb7d6b1f0a8884901c4cd369a3d4a4123dc3c5799",
        "API_KEY_SHA256_OTHER_MERCHANT_BACKOFFICE=937158f3e31cbaa45fab25a1a6d82b119eb8e17bd9c1ffdcea3d85e1692a43cd"})
class PaymentApiIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ECOMMERCE_KEY = "dev-ecommerce-key-local";
    private static final String MERCHANT_KEY = "dev-merchant-bo-key-change-me";
    private static final String OTHER_MERCHANT_KEY = "dev-other-merchant-key-change-me";
    private static final String CUSTOMER_EMAIL = "jane.doe@example.com";

    @Autowired
    TestRestTemplate http;
    @Autowired
    AesGcmPayloadCipher cipher;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void full_lifecycle_authorize_capture_partial_refunds() {
        String key = newKey();
        ResponseEntity<JsonNode> auth = authorize(key, 49_90, "tok_visa_4242");

        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(auth.getBody().get("status").asText()).isEqualTo("AUTHORIZED");
        String id = auth.getBody().get("payment_id").asText();

        ResponseEntity<JsonNode> capture = merchant(MERCHANT_KEY, "/" + id + "/capture", null, Map.of());
        assertThat(capture.getBody().get("status").asText()).isEqualTo("CAPTURED");

        merchant(MERCHANT_KEY, "/" + id + "/refund", newKey(), Map.of("amount_minor", 10_00));
        ResponseEntity<JsonNode> last = merchant(MERCHANT_KEY, "/" + id + "/refund", newKey(), Map.of("amount_minor", 39_90));

        assertThat(last.getBody().get("status").asText()).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("select count(*) from payment_operation where payment_id = ?::uuid",
                Integer.class, id)).isEqualTo(4);
    }

    @Test
    void database_never_contains_consumer_plaintext() {
        String key = newKey();
        String id = authorize(key, 12_00, "tok_visa_4242").getBody().get("payment_id").asText();

        byte[] stored = jdbc.queryForObject("select encrypted_payload from payment_transaction where id = ?::uuid",
                byte[].class, id);
        String row = jdbc.queryForObject("select row_to_json(t)::text from payment_transaction t where id = ?::uuid",
                String.class, id);

        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(CUSTOMER_EMAIL);
        assertThat(row).doesNotContain(CUSTOMER_EMAIL).doesNotContain("Doe");
    }

    @Test
    void replay_returns_same_payment_and_conflicting_replay_is_422() {
        String key = newKey();
        String body = payloadFor(key);
        ResponseEntity<JsonNode> first = authorize(key, 20_00, "tok_visa_4242", body);
        ResponseEntity<JsonNode> replay = authorize(key, 20_00, "tok_visa_4242", body);
        ResponseEntity<JsonNode> conflict = authorize(key, 21_00, "tok_visa_4242", body);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().get("payment_id")).isEqualTo(first.getBody().get("payment_id"));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(conflict.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(conflict.getBody().get("code").asText()).isEqualTo("idempotency_key_reused");
    }

    @Test
    void concurrent_requests_with_same_key_create_a_single_payment() throws Exception {
        String key = newKey();
        String body = payloadFor(key);
        List<Callable<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> authorize(key, 15_00, "tok_visa_4242", body));
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (Future<ResponseEntity<JsonNode>> f : pool.invokeAll(calls)) {
                // Either created, replayed, or lost the optimistic-lock race on the INITIATED retry path.
                assertThat(f.get().getStatusCode().value()).isIn(200, 201, 409);
            }
        }

        assertThat(jdbc.queryForObject("select count(*) from payment_transaction where idempotency_key = ?",
                Integer.class, key)).isEqualTo(1);
    }

    @Test
    void psp_decline_creates_a_failed_payment_not_an_http_error() {
        ResponseEntity<JsonNode> response = authorize(newKey(), 10_00, SimulatedSandboxPspApi.DECLINE_TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status").asText()).isEqualTo("FAILED");
        assertThat(response.getBody().get("failure_code").asText()).isEqualTo("card_declined");
    }

    @Test
    void psp_unavailable_is_503_with_retry_after_and_payment_stays_initiated() {
        String key = newKey();
        ResponseEntity<JsonNode> response = authorize(key, 10_00, SimulatedSandboxPspApi.UNAVAILABLE_TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(jdbc.queryForObject("select status from payment_transaction where idempotency_key = ?",
                String.class, key)).isEqualTo("INITIATED");
    }

    @Test
    void payload_copied_from_another_payment_is_rejected_by_aes_gcm_binding() {
        String payloadOfAnotherPayment = payloadFor(newKey());
        ResponseEntity<JsonNode> response = authorize(newKey(), 10_00, "tok_visa_4242", payloadOfAnotherPayment);

        assertThat(response.getBody().get("status").asText()).isEqualTo("FAILED");
        assertThat(response.getBody().get("failure_code").asText()).isEqualTo("invalid_consumer_payload");
    }

    @Test
    void channels_are_isolated() {
        String id = authorize(newKey(), 10_00, "tok_visa_4242").getBody().get("payment_id").asText();

        // ECOMMERCE key cannot capture
        assertThat(merchant(ECOMMERCE_KEY, "/" + id + "/capture", null, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // No key at all
        assertThat(merchant("wrong-key", "/" + id + "/capture", null, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // Another merchant's back-office cannot see it (404, not 403: no existence oracle)
        assertThat(merchant(OTHER_MERCHANT_KEY, "/" + id + "/capture", null, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void raw_card_number_is_refused_at_the_door() {
        ResponseEntity<JsonNode> response = authorize(newKey(), 10_00, "4111111111111111");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("detail").asText()).doesNotContain("4111111111111111");
    }

    @Test
    void cannot_refund_more_than_captured() {
        String id = authorize(newKey(), 10_00, "tok_visa_4242").getBody().get("payment_id").asText();
        merchant(MERCHANT_KEY, "/" + id + "/capture", null, Map.of("amount_minor", 6_00));

        ResponseEntity<JsonNode> response = merchant(MERCHANT_KEY, "/" + id + "/refund", newKey(),
                Map.of("amount_minor", 7_00));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("amount_exceeded");
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<JsonNode> authorize(String key, long amount, String token) {
        return authorize(key, amount, token, payloadFor(key));
    }

    private ResponseEntity<JsonNode> authorize(String key, long amount, String token, String payload) {
        HttpHeaders headers = headers(ECOMMERCE_KEY);
        headers.set("Idempotency-Key", key);
        Map<String, Object> body = Map.of(
                "amount_minor", amount,
                "currency", "EUR",
                "psp_token", token,
                "encrypted_consumer_payload", payload,
                "payload_key_id", "dev-2026-11");
        return http.exchange("/api/v1/checkout/payments", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> merchant(String apiKey, String path, String idempotencyKey, Map<String, Object> body) {
        HttpHeaders headers = headers(apiKey);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return http.exchange("/api/v1/merchant/payments" + path, HttpMethod.POST, new HttpEntity<>(body, headers),
                JsonNode.class);
    }

    private static HttpHeaders headers(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", apiKey);
        return headers;
    }

    /** What the checkout app does: encrypt its own data, bound to (merchant, idempotency key). */
    private String payloadFor(String idempotencyKey) {
        String json = """
                {"order_reference":"ORD-42",
                 "customer":{"email":"%s","first_name":"Jane","last_name":"Doe"},
                 "billing_address":{"line1":"1 rue de la Paix","postal_code":"75002","city":"Paris","country_code":"FR"}}
                """.formatted(CUSTOMER_EMAIL);
        byte[] aad = AesGcmPayloadCipher.associatedData(new MerchantId("merchant-demo"), new IdempotencyKey(idempotencyKey));
        return Base64.getEncoder().encodeToString(
                cipher.encrypt("dev-2026-11", json.getBytes(StandardCharsets.UTF_8), aad).ciphertext());
    }

    private static String newKey() {
        return "it-" + UUID.randomUUID();
    }
}
