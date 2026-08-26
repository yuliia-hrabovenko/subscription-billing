package com.subscriptionbilling.billingjob.planchange;

import java.util.UUID;

/**
 * The seam a due Subscription's pending Plan change is applied through, before the
 * billing job computes this cycle's charge, so it never depends on billing-core's
 * Subscription/Plan persistence directly. An implementation owns deciding whether the
 * pending target Plan is free and persisting the change accordingly.
 */
public interface PendingPlanChangePort {

    /**
     * Applies {@code subscriptionId}'s pending Plan change, if one exists, so any charge
     * attempted for this cycle reflects the new Plan rather than the one being switched
     * away from.
     *
     * @param subscriptionId a due Subscription's id
     * @return the outcome, telling the caller whether and how to proceed with a charge
     *         attempt for this cycle
     */
    PendingPlanChangeOutcome applyIfPending(UUID subscriptionId);
}
