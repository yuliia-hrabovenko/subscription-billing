package com.subscriptionbilling.api.support;

import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.UUID;

/**
 * Wires a deterministic {@link PaymentGatewayClient} for this module's full-context
 * tests, standing in for the real gateway adapter (not built yet — see the Payment
 * Gateway Integration spec). The outcome is selected by {@code paymentMethodToken} (mirroring
 * {@code billing-job}'s test-only {@code FakePaymentGatewayClient}, not reusable here since
 * it lives in that module's test sources) so a test can drive each {@link ChargeResult}
 * case without stubbing an HTTP call; any other token succeeds. {@code @TestConfiguration}
 * keeps it out of component scanning, so it only takes effect where explicitly imported
 * (see {@link AbstractPostgresIntegrationTest}).
 */
@TestConfiguration
public class PaymentGatewayTestConfig {

    /** Card-on-file token that always resolves to {@link ChargeResult.Declined}. */
    public static final String DECLINE_TOKEN = "tok_decline";

    /** Card-on-file token that always resolves to {@link ChargeResult.FailedTransiently}. */
    public static final String TRANSIENT_FAILURE_TOKEN = "tok_transient_failure";

    @Bean
    public PaymentGatewayClient paymentGatewayClient() {
        return (paymentMethodToken, amount) -> {
            if (DECLINE_TOKEN.equals(paymentMethodToken)) {
                return new ChargeResult.Declined("card_declined");
            }
            if (TRANSIENT_FAILURE_TOKEN.equals(paymentMethodToken)) {
                return new ChargeResult.FailedTransiently("gateway_timeout");
            }
            return new ChargeResult.Succeeded("test-txn-" + UUID.randomUUID());
        };
    }
}
