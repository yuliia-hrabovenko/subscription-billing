package com.subscriptionbilling.payments.paymentmethod;

import com.subscriptionbilling.billingjob.paymentmethod.CustomerPaymentMethodPort;
import com.subscriptionbilling.billingjob.paymentmethod.PaymentMethodOnboardingPort;
import com.subscriptionbilling.payments.gateway.StripeProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers this module's card-on-file beans for any Spring context that does not already
 * define one, following {@link com.subscriptionbilling.payments.gateway.StripeGatewayAutoConfiguration}'s
 * pattern: runs as a Spring Boot auto-configuration, which loads after every explicitly
 * declared or {@code @TestConfiguration}-imported bean, so {@link ConditionalOnMissingBean}
 * reliably backs off wherever a fake is wired for a test.
 */
@AutoConfiguration
@EnableConfigurationProperties(StripeProperties.class)
public class PaymentMethodAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(StripePaymentMethodClient.class)
    public StripePaymentMethodClient stripePaymentMethodClient(StripeProperties stripeProperties) {
        return new StripePaymentMethodClient(stripeProperties);
    }

    @Bean
    @ConditionalOnMissingBean(PaymentMethodOnboardingPort.class)
    public PaymentMethodOnboardingPort paymentMethodOnboardingPort(StripePaymentMethodClient stripePaymentMethodClient,
                                                                     PaymentMethodRepository paymentMethodRepository) {
        return new PaymentMethodOnboardingService(stripePaymentMethodClient, paymentMethodRepository);
    }

    @Bean
    @ConditionalOnMissingBean(CustomerPaymentMethodPort.class)
    public CustomerPaymentMethodPort customerPaymentMethodPort(PaymentMethodRepository paymentMethodRepository) {
        return new PaymentMethodLookupAdapter(paymentMethodRepository);
    }

    @Bean
    @ConditionalOnMissingBean(SetupIntentPort.class)
    public SetupIntentPort setupIntentPort(StripePaymentMethodClient stripePaymentMethodClient) {
        return new SetupIntentService(stripePaymentMethodClient);
    }
}
