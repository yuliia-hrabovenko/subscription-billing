package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingjob.due.DueSubscriptionsPort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * This module's implementation of the billing-job module's {@link DueSubscriptionsPort}
 * contract, backed by {@link SubscriptionRepository#findDueSubscriptionIds}.
 */
@Component
public class DueSubscriptionsAdapter implements DueSubscriptionsPort {

    private final SubscriptionRepository subscriptionRepository;

    public DueSubscriptionsAdapter(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    @Override
    public List<UUID> findDueSubscriptionIds(LocalDate asOf) {
        return subscriptionRepository.findDueSubscriptionIds(asOf);
    }
}
