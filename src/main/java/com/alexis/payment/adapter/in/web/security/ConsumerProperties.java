package com.alexis.payment.adapter.in.web.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Registered consumer applications. Only the SHA-256 of each API key is configured (K8s Secret in real
 * deployments), so a config leak does not leak usable keys.
 */
@ConfigurationProperties(prefix = "payment")
public record ConsumerProperties(List<Consumer> consumers) {

    public ConsumerProperties {
        consumers = consumers == null ? List.of() : List.copyOf(consumers);
    }

    public record Consumer(String id, ConsumerChannel channel, String merchantId, String apiKeySha256) {
    }
}
