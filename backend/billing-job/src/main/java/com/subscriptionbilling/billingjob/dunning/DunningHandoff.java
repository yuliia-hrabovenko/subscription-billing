package com.subscriptionbilling.billingjob.dunning;

import java.util.UUID;

/**
 * The seam a failed renewal or Trial-conversion charge is handed off through, so the
 * billing job drives the charge attempt without owning suspension/retry policy itself.
 * An implementation decides what happens next to the Subscription.
 */
public interface DunningHandoff {

    /**
     * Reacts to a failed charge.
     *
     * @param subscriptionId the Subscription the failed charge was attempted against
     * @param invoiceId      the Invoice the failed Payment Attempt belongs to
     */
    void onChargeFailed(UUID subscriptionId, UUID invoiceId);
}
