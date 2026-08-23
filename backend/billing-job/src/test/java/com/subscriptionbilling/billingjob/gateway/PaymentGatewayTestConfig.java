package com.subscriptionbilling.billingjob.gateway;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Wires {@link FakePaymentGatewayClient} as the active {@link PaymentGatewayClient} bean
 * for this module's own Spring-context tests. {@code @TestConfiguration} keeps it out of
 * component scanning, so a test only picks it up by explicitly importing it. The real
 * adapter replaces this behind the same {@link PaymentGatewayClient} interface; no consumer
 * of the interface needs to change when that happens.
 */
@TestConfiguration
public class PaymentGatewayTestConfig {

    @Bean
    public PaymentGatewayClient paymentGatewayClient() {
        return new FakePaymentGatewayClient();
    }
}
