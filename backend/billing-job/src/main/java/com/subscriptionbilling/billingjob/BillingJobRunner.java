package com.subscriptionbilling.billingjob;

import com.subscriptionbilling.billingjob.due.DueSubscriptionsPort;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Entry point for the daily billing job. Exposes a single public, directly-invokable
 * method so a Kubernetes CronJob, a manual operator run, and a test all go through the
 * same code path — none of them go through a {@code @Scheduled} annotation. Charging the
 * selected Subscriptions is not this class's responsibility yet; it only proves
 * selection.
 */
@Component
public class BillingJobRunner {

    private final DueSubscriptionsPort dueSubscriptionsPort;
    private final Clock clock;

    public BillingJobRunner(DueSubscriptionsPort dueSubscriptionsPort) {
        this(dueSubscriptionsPort, Clock.systemUTC());
    }

    BillingJobRunner(DueSubscriptionsPort dueSubscriptionsPort, Clock clock) {
        this.dueSubscriptionsPort = dueSubscriptionsPort;
        this.clock = clock;
    }

    /**
     * Selects every Subscription due for a charge as of today.
     *
     * @return the ids of the selected Subscriptions, per {@link
     *         DueSubscriptionsPort#findDueSubscriptionIds}'s due-date <= today rule
     */
    public List<UUID> run() {
        return dueSubscriptionsPort.findDueSubscriptionIds(LocalDate.now(clock));
    }
}
