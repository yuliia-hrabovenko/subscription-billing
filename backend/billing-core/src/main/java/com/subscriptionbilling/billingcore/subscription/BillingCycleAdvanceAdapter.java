package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingjob.anchor.BillingCycleAdvancePort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * This module's implementation of the billing-job module's {@link
 * BillingCycleAdvancePort} contract, backed by {@link Subscription#advanceDueDate}.
 */
@Component
public class BillingCycleAdvanceAdapter implements BillingCycleAdvancePort {

    private final SubscriptionRepository subscriptionRepository;

    public BillingCycleAdvanceAdapter(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    @Override
    @Transactional
    public void advanceDueDate(UUID subscriptionId, LocalDate nextDueDate) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("Subscription " + subscriptionId + " does not exist"));
        subscription.advanceDueDate(nextDueDate);
        subscriptionRepository.saveAndFlush(subscription);
    }
}
