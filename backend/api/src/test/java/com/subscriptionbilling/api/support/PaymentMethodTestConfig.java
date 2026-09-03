package com.subscriptionbilling.api.support;

import com.subscriptionbilling.billingjob.paymentmethod.CustomerPaymentMethodPort;
import com.subscriptionbilling.billingjob.paymentmethod.PaymentMethodOnboardingPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Wires the fake payment-method ports as this module's full-context tests' active beans,
 * mirroring {@link PaymentGatewayTestConfig}'s reasoning exactly. Registers {@link
 * FakePaymentMethodStore} as its own bean too, so a test can autowire it directly to seed
 * a hand-constructed Customer's card (see its Javadoc).
 */
@TestConfiguration
public class PaymentMethodTestConfig {

    @Bean
    public FakePaymentMethodStore fakePaymentMethodStore() {
        return new FakePaymentMethodStore();
    }

    @Bean
    public PaymentMethodOnboardingPort paymentMethodOnboardingPort(FakePaymentMethodStore store) {
        return new FakePaymentMethodOnboardingPort(store);
    }

    @Bean
    public CustomerPaymentMethodPort customerPaymentMethodPort(FakePaymentMethodStore store) {
        return new FakeCustomerPaymentMethodPort(store);
    }
}
