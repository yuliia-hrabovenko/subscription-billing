package com.subscriptionbilling.api.support;

import com.subscriptionbilling.billingjob.paymentmethod.CustomerPaymentMethodPort;
import com.subscriptionbilling.billingjob.paymentmethod.ProviderPaymentMethodReference;

import java.util.Optional;
import java.util.UUID;

/**
 * Placeholder {@link CustomerPaymentMethodPort} for this module's full-context tests --
 * see {@link FakePaymentMethodOnboardingPort}'s Javadoc for the shared-store reasoning.
 */
public class FakeCustomerPaymentMethodPort implements CustomerPaymentMethodPort {

    private final FakePaymentMethodStore store;

    public FakeCustomerPaymentMethodPort(FakePaymentMethodStore store) {
        this.store = store;
    }

    @Override
    public Optional<ProviderPaymentMethodReference> findActiveProviderReference(UUID customerId) {
        return store.get(customerId);
    }
}
