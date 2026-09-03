package com.subscriptionbilling.billingcore.support;

import com.subscriptionbilling.billingjob.paymentmethod.CustomerPaymentMethodPort;
import com.subscriptionbilling.billingjob.paymentmethod.PaymentMethodOnboardingPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Wires {@link FakePaymentMethodOnboardingPort}/{@link FakeCustomerPaymentMethodPort} as
 * this module's full-context tests' active beans, mirroring {@link
 * PaymentGatewayTestConfig}'s reasoning exactly. {@code @TestConfiguration} keeps it out
 * of component scanning, so it only takes effect where explicitly imported (see {@link
 * AbstractPostgresIntegrationTest}).
 */
@TestConfiguration
public class PaymentMethodTestConfig {

    @Bean
    public PaymentMethodOnboardingPort paymentMethodOnboardingPort() {
        return new FakePaymentMethodOnboardingPort();
    }

    @Bean
    public CustomerPaymentMethodPort customerPaymentMethodPort() {
        return new FakeCustomerPaymentMethodPort();
    }
}
