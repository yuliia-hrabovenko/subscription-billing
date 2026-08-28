package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.invoicing.invoice.SubscriptionOwnershipPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * This module's implementation of the invoicing module's {@link SubscriptionOwnershipPort}
 * contract, backed by {@link Subscription}'s own Customer relation.
 */
@Component
public class SubscriptionOwnershipAdapter implements SubscriptionOwnershipPort {

    private final SubscriptionRepository subscriptionRepository;

    public SubscriptionOwnershipAdapter(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOwnedByCustomer(UUID subscriptionId, UUID customerId) {
        return subscriptionRepository.findById(subscriptionId)
                .map(subscription -> subscription.getCustomer().getId().equals(customerId))
                .orElse(false);
    }
}
