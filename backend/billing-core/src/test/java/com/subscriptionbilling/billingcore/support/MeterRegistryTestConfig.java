package com.subscriptionbilling.billingcore.support;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Production supplies a {@link MeterRegistry} bean via the {@code api} module's
 * actuator/Prometheus dependency, not this library module's own -- this test-only
 * substitute exists so {@code notifications}' {@code OutboxRelayMetrics} (pulled in
 * transitively, since {@link AbstractPostgresIntegrationTest}'s subclasses boot a full
 * context) can be constructed. Mirrors {@code notifications}' own {@code
 * OutboxRelayKafkaIT.MetricsTestConfig}.
 */
@TestConfiguration
public class MeterRegistryTestConfig {

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
