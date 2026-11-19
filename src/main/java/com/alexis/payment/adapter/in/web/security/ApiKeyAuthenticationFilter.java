package com.alexis.payment.adapter.in.web.security;

import com.alexis.payment.domain.model.MerchantId;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Minimal service-to-service authentication: {@code X-Api-Key} -> consumer -> channel + merchant scope.
 * Deliberately simple for a lab (see ADR-0002 for the mTLS / OAuth2 client-credentials target).
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";

    private final List<RegisteredConsumer> consumers;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(ConsumerProperties properties, ObjectMapper objectMapper) {
        this.consumers = properties.consumers().stream()
                .map(c -> new RegisteredConsumer(HexFormat.of().parseHex(c.apiKeySha256()),
                        new ConsumerPrincipal(c.id(), c.channel(), new MerchantId(c.merchantId()))))
                .toList();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiKey = request.getHeader(HEADER);
        ConsumerPrincipal principal = apiKey == null ? null : authenticate(apiKey);
        if (principal == null) {
            reject(response, HttpStatus.UNAUTHORIZED, "unauthenticated", "Missing or invalid " + HEADER);
            return;
        }
        if (!principal.channel().allows(request.getRequestURI())) {
            reject(response, HttpStatus.FORBIDDEN, "wrong_channel",
                    "Consumer channel " + principal.channel() + " cannot call this endpoint");
            return;
        }
        request.setAttribute(ConsumerPrincipal.REQUEST_ATTRIBUTE, principal);
        chain.doFilter(request, response);
    }

    private ConsumerPrincipal authenticate(String apiKey) {
        byte[] presented = sha256(apiKey);
        ConsumerPrincipal match = null;
        for (RegisteredConsumer consumer : consumers) {
            // Constant-time comparison, and no early exit, to avoid timing oracles.
            if (MessageDigest.isEqual(consumer.keyHash(), presented)) {
                match = consumer.principal();
            }
        }
        return match;
    }

    private void reject(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:payment-gateway:problem:" + code));
        problem.setProperty("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record RegisteredConsumer(byte[] keyHash, ConsumerPrincipal principal) {
    }
}
