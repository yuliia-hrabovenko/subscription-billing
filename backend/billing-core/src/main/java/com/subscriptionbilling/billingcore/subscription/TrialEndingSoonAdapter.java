package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingjob.trial.TrialEndingSoonPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * This module's implementation of the billing-job module's {@link TrialEndingSoonPort}
 * contract, backed by {@link SubscriptionRepository#findTrialsEndingSoonIds} and {@link
 * SubscriptionService#notifyTrialEndingSoon}.
 */
@Component
public class TrialEndingSoonAdapter implements TrialEndingSoonPort {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;

    public TrialEndingSoonAdapter(SubscriptionRepository subscriptionRepository, SubscriptionService subscriptionService) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionService = subscriptionService;
    }

    @Override
    public List<UUID> findTrialsEndingSoon(Instant leadTimeCutoff) {
        return subscriptionRepository.findTrialsEndingSoonIds(SubscriptionState.TRIALING, leadTimeCutoff);
    }

    @Override
    public void notifyTrialEndingSoon(UUID subscriptionId, Instant notifiedAt) {
        subscriptionService.notifyTrialEndingSoon(subscriptionId, notifiedAt);
    }
}
