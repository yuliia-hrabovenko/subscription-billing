package com.subscriptionbilling.billingjob.dunning;

/**
 * Outcome of a {@link DunningRetryCharge#attempt} call. {@link Recovered} and {@link
 * Declined} mean the charge, its Payment Attempt, and any resulting {@code
 * suspended -> active} or {@code suspended -> canceled} transition are already
 * persisted; {@link FailedTransiently} means nothing was recorded and the retry slot
 * was not consumed.
 */
public sealed interface DunningRetryChargeResult {

    /** @param gatewayTransactionId the gateway's reference for the completed charge */
    record Recovered(String gatewayTransactionId) implements DunningRetryChargeResult {
    }

    /**
     * @param outcome whether the retry bound was reached (the Subscription was
     *                canceled) or the next Dunning offset was scheduled
     * @param reason  the gateway's stated decline reason
     */
    record Declined(DunningRetryOutcome outcome, String reason) implements DunningRetryChargeResult {
    }

    /** @param reason the gateway- or network-side condition that prevented a result */
    record FailedTransiently(String reason) implements DunningRetryChargeResult {
    }
}
