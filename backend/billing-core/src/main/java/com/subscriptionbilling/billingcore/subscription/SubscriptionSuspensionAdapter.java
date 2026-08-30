package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingjob.ChargeTrigger;
import com.subscriptionbilling.dunning.SubscriptionSuspensionPort;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

/**
 * This module's implementation of the dunning module's {@link SubscriptionSuspensionPort}
 * contract, backed by {@link SubscriptionService#suspend}, {@link
 * SubscriptionService#scheduleRetry}, and {@link SubscriptionService#cancelForDunningExhaustion}.
 *
 * <p>{@code subscriptionService} is injected lazily to break the bean creation cycle
 * {@code SubscriptionService -> DunningRetryCharge -> SuspendAndScheduleRetryDunningHandoff
 * -> SubscriptionSuspensionAdapter -> SubscriptionService}: this adapter only calls into
 * it once dunning actually suspends/retries/cancels a Subscription, never during bean
 * construction, so resolving it on first use rather than eagerly is safe.
 */
@Component
public class SubscriptionSuspensionAdapter implements SubscriptionSuspensionPort {

    private final SubscriptionService subscriptionService;

    public SubscriptionSuspensionAdapter(@Lazy SubscriptionService subscriptionService) {
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
    public void cancel(UUID subscriptionId, String correlationId, ChargeTrigger trigger) {
        subscriptionService.cancelForDunningExhaustion(subscriptionId, correlationId, trigger);
    }
}
