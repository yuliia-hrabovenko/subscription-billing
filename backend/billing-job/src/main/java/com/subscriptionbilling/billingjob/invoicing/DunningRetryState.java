package com.subscriptionbilling.billingjob.invoicing;

import java.time.Instant;

/**
 * An Invoice's Dunning retry bookkeeping, reported by {@link
 * ChargeRecordingPort#recordRetryAttempt} immediately after recording one more retry
 * Payment Attempt against it.
 *
 * @param initialFailureAt the Invoice's initial failed Payment Attempt instant — the
 *                         basis the next Dunning offset (day 3, then day 7) is computed
 *                         from
 * @param retriesUsed      how many retry Payment Attempts, this one included, have now
 *                         been recorded against the Invoice
 * @param retriesExhausted whether the retry bound has now been reached
 */
public record DunningRetryState(Instant initialFailureAt, int retriesUsed, boolean retriesExhausted) {
}
