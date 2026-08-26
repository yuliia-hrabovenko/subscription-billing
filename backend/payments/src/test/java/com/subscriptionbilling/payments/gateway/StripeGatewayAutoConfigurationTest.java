package com.subscriptionbilling.payments.gateway;

import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link StripeGatewayAutoConfiguration}'s bean-registration contract: the real
 * adapter is the {@link PaymentGatewayClient} bean when nothing else provides one, and
 * it backs off — leaving an already-defined fake in place — otherwise.
 */
class StripeGatewayAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StripeGatewayAutoConfiguration.class));

    @Test
    void registersTheStripeAdapterWhenNoOtherPaymentGatewayClientBeanExists() {
        contextRunner.run(context ->
                assertThat(context.getBean(PaymentGatewayClient.class)).isInstanceOf(StripePaymentGatewayClient.class));
    }

    @Test
    void backsOffWhenAnotherPaymentGatewayClientBeanIsAlreadyDefined() {
        contextRunner.withUserConfiguration(FakeGatewayConfig.class).run(context ->
                assertThat(context.getBean(PaymentGatewayClient.class)).isNotInstanceOf(StripePaymentGatewayClient.class));
    }

    @Configuration
    static class FakeGatewayConfig {

        @Bean
        PaymentGatewayClient paymentGatewayClient() {
            return new PaymentGatewayClient() {
                @Override
                public ChargeResult charge(String paymentMethodToken, BigDecimal amount) {
                    return new ChargeResult.Succeeded("fake-txn");
                }

                @Override
                public boolean verifyWebhookSignature(String payload, String signatureHeader) {
                    return true;
                }
            };
        }
    }
}
