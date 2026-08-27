package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.dunning.SubscriptionSuspensionPort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

/**
 * This module's implementation of the dunning module's {@link SubscriptionSuspensionPort}
 * contract, backed by {@link SubscriptionService#suspend}, {@link
 * SubscriptionService#scheduleRetry}, and {@link SubscriptionService#cancelForDunningExhaustion}.
 */
@Component
public class SubscriptionSuspensionAdapter implements SubscriptionSuspensionPort {

    private final SubscriptionService subscriptionService;

    public SubscriptionSuspensionAdapter(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @Override
    public void suspend(UUID subscriptionId, LocalDate billingPeriod, String correlationId) {
        subscriptionService.suspend(subscriptionId, billingPeriod, correlationId);
    }

    @Override
    public void scheduleRetry(UUID subscriptionId, LocalDate retryDueDate) {
        subscriptionService.scheduleRetry(subscriptionId, retryDueDate);
    }

    @Override
    public void cancel(UUID subscriptionId, String correlationId) {
        subscriptionService.cancelForDunningExhaustion(subscriptionId, correlationId);
    }
}
