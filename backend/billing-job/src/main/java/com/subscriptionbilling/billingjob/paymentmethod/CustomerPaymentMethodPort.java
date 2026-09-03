package com.subscriptionbilling.billingjob.paymentmethod;

import java.util.Optional;
import java.util.UUID;

/**
 * The seam billing resolves a due Subscription's card-on-file reference through, so it
 * never depends on how or where that reference is actually stored -- the read-side
 * counterpart to {@link PaymentMethodOnboardingPort}.
 */
public interface CustomerPaymentMethodPort {

    /**
     * @param customerId the Customer to resolve a card on file for
     * @return the gateway-provided references to charge against, or empty if this Customer
     *         has no payment method on file
     */
    Optional<ProviderPaymentMethodReference> findActiveProviderReference(UUID customerId);
}
