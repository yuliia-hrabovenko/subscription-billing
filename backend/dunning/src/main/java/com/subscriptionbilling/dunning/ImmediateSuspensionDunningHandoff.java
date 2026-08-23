package com.subscriptionbilling.dunning;

import com.subscriptionbilling.billingjob.dunning.DunningHandoff;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Default {@link DunningHandoff}: suspends the Subscription immediately, no grace
 * period, matching the domain model's first-failed-charge-suspends business rule. This
 * is real production behavior, not a stand-in — only the day 1/3/7 retry schedule and
 * self-service recovery are missing. A later extension of this module replaces this
 * default behind the same {@link DunningHandoff} interface; no caller of the interface
 * needs to change when that happens.
 */
@Component
public class ImmediateSuspensionDunningHandoff implements DunningHandoff {

    private final SubscriptionSuspensionPort subscriptionSuspensionPort;

    public ImmediateSuspensionDunningHandoff(SubscriptionSuspensionPort subscriptionSuspensionPort) {
        this.subscriptionSuspensionPort = subscriptionSuspensionPort;
    }

    /**
     * Suspends the Subscription identified by {@code subscriptionId}. {@code
     * invoiceId} is passed through as the correlation ID, tying the suspension back to
     * the specific failed Payment Attempt.
     *
     * @param subscriptionId the Subscription to suspend
     * @param invoiceId      the Invoice the failed Payment Attempt belongs to
     */
    @Override
    public void onChargeFailed(UUID subscriptionId, UUID invoiceId) {
        subscriptionSuspensionPort.suspend(subscriptionId, invoiceId.toString());
    }
}
