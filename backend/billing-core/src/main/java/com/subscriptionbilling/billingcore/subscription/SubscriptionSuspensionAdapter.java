package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.dunning.SubscriptionSuspensionPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * This module's implementation of the dunning module's {@link SubscriptionSuspensionPort}
 * contract, backed by {@link SubscriptionService#suspend}.
 */
@Component
public class SubscriptionSuspensionAdapter implements SubscriptionSuspensionPort {

    private final SubscriptionService subscriptionService;

    public SubscriptionSuspensionAdapter(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @Override
    public void suspend(UUID subscriptionId, String correlationId) {
        subscriptionService.suspend(subscriptionId, correlationId);
    }
}
