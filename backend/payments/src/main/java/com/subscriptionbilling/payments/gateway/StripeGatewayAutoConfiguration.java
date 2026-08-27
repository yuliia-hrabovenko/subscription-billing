package com.subscriptionbilling.payments.gateway;

import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link StripePaymentGatewayClient}, wrapped in {@link
 * ResilientPaymentGatewayClient}'s infra-level retry/circuit-breaker, as the {@link
 * PaymentGatewayClient} bean for any Spring context that does not already define one.
 * Runs as a Spring Boot auto-configuration, which loads after every explicitly declared or
 * {@code @TestConfiguration}-imported bean, so {@link ConditionalOnMissingBean} reliably
 * backs off wherever a fake gateway is wired for a test or profile — no other module's
 * config needs to change for the real adapter to become the active bean everywhere else.
 */
@AutoConfiguration
@EnableConfigurationProperties({StripeProperties.class, GatewayResilienceProperties.class})
public class StripeGatewayAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PaymentGatewayClient.class)
    public PaymentGatewayClient paymentGatewayClient(StripeProperties stripeProperties,
                                                       GatewayResilienceProperties resilienceProperties) {
        return new ResilientPaymentGatewayClient(new StripePaymentGatewayClient(stripeProperties), resilienceProperties);
    }
}
