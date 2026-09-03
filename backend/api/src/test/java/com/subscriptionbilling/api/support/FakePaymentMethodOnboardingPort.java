package com.subscriptionbilling.api.support;

import com.subscriptionbilling.billingjob.paymentmethod.PaymentMethodDetails;
import com.subscriptionbilling.billingjob.paymentmethod.PaymentMethodOnboardingPort;

import java.util.UUID;

/**
 * Placeholder {@link PaymentMethodOnboardingPort} for this module's full-context tests,
 * standing in for the real Stripe-backed adapter (not built against a real Stripe account
 * in tests, mirroring {@link PaymentGatewayTestConfig}'s reasoning for {@code
 * PaymentGatewayClient}). Records the attachment into the shared {@link
 * FakePaymentMethodStore} rather than persisting a real {@code payment_method} row, and
 * always "succeeds" with fixed placeholder card metadata -- no test in this module
 * asserts on the metadata itself.
 */
public class FakePaymentMethodOnboardingPort implements PaymentMethodOnboardingPort {

    private final FakePaymentMethodStore store;

    public FakePaymentMethodOnboardingPort(FakePaymentMethodStore store) {
        this.store = store;
    }

    @Override
    public PaymentMethodDetails attach(UUID customerId, String providerPaymentMethodId) {
        store.put(customerId, providerPaymentMethodId);
        return new PaymentMethodDetails(providerPaymentMethodId, "card", "visa", "4242", 12, 2030);
    }
}
