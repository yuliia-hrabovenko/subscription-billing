package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.dunning.SubscriptionSuspensionPort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

/**
 * This module's implementation of the dunning module's {@link SubscriptionSuspensionPort}
 * contract, backed by {@link SubscriptionService#suspend} and {@link
 * SubscriptionService#scheduleRetry}.
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

    @Override
    public void scheduleRetry(UUID subscriptionId, LocalDate retryDueDate) {
        subscriptionService.scheduleRetry(subscriptionId, retryDueDate);
    }
}
