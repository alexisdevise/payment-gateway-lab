package com.alexis.payment.config;

import com.alexis.payment.adapter.in.web.security.ApiKeyAuthenticationFilter;
import com.alexis.payment.adapter.in.web.security.ConsumerProperties;
import com.alexis.payment.adapter.out.crypto.AesGcmPayloadCipher;
import com.alexis.payment.adapter.out.psp.PspConnector;
import com.alexis.payment.adapter.out.psp.RoutingPspAdapter;
import com.alexis.payment.adapter.out.psp.sandbox.SandboxPspConnector;
import com.alexis.payment.adapter.out.psp.sandbox.SimulatedSandboxPspApi;
import com.alexis.payment.application.PaymentService;
import com.alexis.payment.domain.port.out.PaymentRepositoryPort;
import com.alexis.payment.domain.port.out.PspAdapterPort;
import com.alexis.payment.domain.port.out.PspContractPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.util.List;

/**
 * Composition root: the only place that knows which adapter implements which port.
 * Domain and application classes carry no Spring annotation.
 */
@Configuration
class PaymentConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PaymentService paymentService(PaymentRepositoryPort payments, PspContractPort contracts, PspAdapterPort psp,
                                  Clock clock) {
        return new PaymentService(payments, contracts, psp, clock);
    }

    @Bean
    AesGcmPayloadCipher payloadCipher(CryptoProperties properties) {
        return new AesGcmPayloadCipher(properties.keys());
    }

    @Bean
    SandboxPspConnector sandboxPspConnector(AesGcmPayloadCipher cipher, ObjectMapper objectMapper) {
        return new SandboxPspConnector(cipher, objectMapper, new SimulatedSandboxPspApi());
    }

    /** Primary: connectors also implement PspAdapterPort, but the application must only see the router. */
    @Bean
    @Primary
    PspAdapterPort pspAdapter(List<PspConnector> connectors) {
        return new RoutingPspAdapter(connectors);
    }

    @Bean
    FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyFilter(ConsumerProperties consumers,
                                                                    ObjectMapper objectMapper) {
        var registration = new FilterRegistrationBean<>(new ApiKeyAuthenticationFilter(consumers, objectMapper));
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}
