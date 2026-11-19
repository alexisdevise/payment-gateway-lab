package com.alexis.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/** keyId -> base64 AES-256 key. Injected from a K8s Secret; no default value in the main profile. */
@ConfigurationProperties(prefix = "payment.crypto")
public record CryptoProperties(Map<String, String> keys) {
}
