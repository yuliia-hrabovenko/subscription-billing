package com.subscriptionbilling.api.webhook;

import com.subscriptionbilling.billingcore.subscription.SubscriptionService;
import com.subscriptionbilling.webhooks.dispatch.DisputeCancellationPort;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * This application's implementation of the webhooks module's {@link
 * DisputeCancellationPort} contract, backed by {@link SubscriptionService#cancelForDispute}.
 * Lives here rather than in billing-core so that module's own narrow test contexts never
 * need to satisfy the webhooks module's own eager dependencies (e.g. a payment gateway
 * client) just to boot — see {@code BillingCoreTestApplication}'s Javadoc for the
 * equivalent reasoning behind its {@code BillingJobRunner} exclusion.
 *
 * <p>{@code subscriptionService} is injected lazily so a narrow slice test elsewhere in
 * this application that happens to pull in {@link WebhookController}'s package doesn't
 * also need to satisfy {@link SubscriptionService}'s full dependency graph.
 */
@Component
public class SubscriptionDisputeCancellationAdapter implements DisputeCancellationPort {

    private final SubscriptionService subscriptionService;

    public SubscriptionDisputeCancellationAdapter(@Lazy SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @Override
    public void cancelForDispute(UUID subscriptionId, String correlationId) {
        subscriptionService.cancelForDispute(subscriptionId, correlationId);
    }
}
