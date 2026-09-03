package com.subscriptionbilling.billingcore.support;

import com.subscriptionbilling.billingjob.paymentmethod.PaymentMethodDetails;
import com.subscriptionbilling.billingjob.paymentmethod.PaymentMethodOnboardingPort;

import java.util.UUID;

/**
 * Placeholder {@link PaymentMethodOnboardingPort} for this module's full-context tests,
 * standing in for the real Stripe-backed adapter. Always "succeeds", echoing
 * the given reference back as the attached card's id, with fixed placeholder card
 * metadata -- no test in this module asserts on the metadata itself.
 */
public class FakePaymentMethodOnboardingPort implements PaymentMethodOnboardingPort {

    @Override
    public PaymentMethodDetails attach(UUID customerId, String providerPaymentMethodId) {
        return new PaymentMethodDetails(providerPaymentMethodId, "card", "visa", "4242", 12, 2030);
    }
}
