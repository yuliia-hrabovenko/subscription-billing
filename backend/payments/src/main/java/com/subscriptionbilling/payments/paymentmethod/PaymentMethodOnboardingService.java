package com.subscriptionbilling.payments.paymentmethod;

import com.subscriptionbilling.billingjob.paymentmethod.PaymentMethodDetails;
import com.subscriptionbilling.billingjob.paymentmethod.PaymentMethodOnboardingPort;

import java.util.UUID;

/**
 * This module's implementation of {@link PaymentMethodOnboardingPort}: creates a Stripe
 * Customer for the given Customer, attaches their just-tokenized PaymentMethod to it, and
 * persists the result as their new card on file, replacing whatever was on file before.
 *
 * <p>Deliberately not {@code @Transactional}: {@link #attach} calls the gateway twice
 * (create Customer, attach PaymentMethod) before ever touching the database, and this
 * module's rule against external calls inside a database transaction applies here exactly
 * as it does to {@link com.subscriptionbilling.payments.gateway.StripePaymentGatewayClient}'s
 * charge path. The delete-then-save that follows runs as two independently transactional
 * repository calls rather than one atomic unit: a crash between them would leave the
 * Customer with no card on file, which only means they're asked to add one again -- not a
 * money-movement correctness issue.
 */
public class PaymentMethodOnboardingService implements PaymentMethodOnboardingPort {

    private static final String STRIPE_PROVIDER = "stripe";

    private final StripePaymentMethodClient stripePaymentMethodClient;
    private final PaymentMethodRepository paymentMethodRepository;

    public PaymentMethodOnboardingService(StripePaymentMethodClient stripePaymentMethodClient,
                                           PaymentMethodRepository paymentMethodRepository) {
        this.stripePaymentMethodClient = stripePaymentMethodClient;
        this.paymentMethodRepository = paymentMethodRepository;
    }

    @Override
    public PaymentMethodDetails attach(UUID customerId, String providerPaymentMethodId) {
        String stripeCustomerId = stripePaymentMethodClient.createCustomer();
        PaymentMethodDetails details = stripePaymentMethodClient.attachPaymentMethod(stripeCustomerId, providerPaymentMethodId);

        paymentMethodRepository.deleteByCustomerId(customerId);
        paymentMethodRepository.save(new PaymentMethod(UUID.randomUUID(), customerId, STRIPE_PROVIDER, stripeCustomerId, details));
        return details;
    }
}
