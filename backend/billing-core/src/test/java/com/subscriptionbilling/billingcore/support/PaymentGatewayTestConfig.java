package com.subscriptionbilling.billingcore.support;

import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Wires {@link FakePaymentGatewayClient} as the active {@link PaymentGatewayClient} bean
 * for this module's full-context tests. {@code @TestConfiguration} keeps it out of
 * component scanning, so it only takes effect where explicitly imported (see {@link
 * AbstractPostgresIntegrationTest}).
 */
@TestConfiguration
public class PaymentGatewayTestConfig {

    @Bean
    public PaymentGatewayClient paymentGatewayClient() {
        return new FakePaymentGatewayClient();
    }
}
