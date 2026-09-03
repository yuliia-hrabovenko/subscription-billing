package com.subscriptionbilling.billingcore.support;

import com.subscriptionbilling.billingjob.paymentmethod.CustomerPaymentMethodPort;
import com.subscriptionbilling.billingjob.paymentmethod.ProviderPaymentMethodReference;

import java.util.Optional;
import java.util.UUID;

/**
 * Placeholder {@link CustomerPaymentMethodPort} for this module's full-context tests,
 * standing in for the real {@code payments}-module-backed adapter. Always reports a
 * deterministic reference derived from the Customer id, so {@link
 * com.subscriptionbilling.billingcore.subscription.ChargeableSubscriptionAdapter} (a
 * real {@code @Component} in this module) has a bean to satisfy at context startup.
 */
public class FakeCustomerPaymentMethodPort implements CustomerPaymentMethodPort {

    @Override
    public Optional<ProviderPaymentMethodReference> findActiveProviderReference(UUID customerId) {
        return Optional.of(new ProviderPaymentMethodReference("fake-cus-" + customerId, "fake-pm-" + customerId));
    }
}
