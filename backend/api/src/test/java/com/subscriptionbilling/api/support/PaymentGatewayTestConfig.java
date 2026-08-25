package com.subscriptionbilling.api.support;

import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.UUID;

/**
 * Wires an always-succeeding {@link PaymentGatewayClient} for this module's full-context
 * tests, standing in for the real gateway adapter (not built yet — see the Payment
 * Gateway Integration spec). {@code @TestConfiguration} keeps it out of component
 * scanning, so it only takes effect where explicitly imported (see {@link
 * AbstractPostgresIntegrationTest}).
 */
@TestConfiguration
public class PaymentGatewayTestConfig {

    @Bean
    public PaymentGatewayClient paymentGatewayClient() {
        return (paymentMethodToken, amount) -> new ChargeResult.Succeeded("test-txn-" + UUID.randomUUID());
    }
}
