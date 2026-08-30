package com.subscriptionbilling.billingjob.dunning;

import com.subscriptionbilling.billingjob.invoicing.DunningRetryState;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The seam a failed renewal/Trial-conversion charge or a failed scheduled Dunning
 * retry is handed off through, so the billing job drives the charge attempt without
 * owning suspension/retry/cancellation policy itself. An implementation decides what
 * happens next to the Subscription.
 */
public interface DunningHandoff {

    /**
     * Reacts to a renewal or Trial-conversion charge failing — the first failure for
     * this Billing Cycle.
     *
     * @param subscriptionId the Subscription the failed charge was attempted against
     * @param invoiceId      the Invoice the failed Payment Attempt belongs to
     * @param billingPeriod  the Billing Cycle date whose charge just failed
     * @param failedAt       the instant the gateway resolved this failed try; an
     *                       implementation may use this as the basis for scheduling a
     *                       retry
     * @param correlationId  the triggering caller's correlation id, carried onto the
     *                       resulting suspension's {@code AuditLogEntry}
     */
    void onChargeFailed(UUID subscriptionId, UUID invoiceId, LocalDate billingPeriod, Instant failedAt, String correlationId);

    /**
     * Reacts to a scheduled Dunning retry (day 1, 3, or 7) failing, deciding whether
     * the bounded retry loop cancels the Subscription or reschedules the next offset.
     *
     * @param subscriptionId the Subscription the failed retry was attempted against
     * @param invoiceId      the Invoice the failed Payment Attempt belongs to
     * @param retryState     the Invoice's retry bookkeeping immediately after this
     *                       failed attempt was recorded
     * @param failedAt       the instant the gateway resolved this failed try
     * @param correlationId  the triggering caller's correlation id, carried onto a
     *                       resulting cancellation's {@code AuditLogEntry}
     * @return whether the Subscription was canceled (retries exhausted) or rescheduled
     */
    DunningRetryOutcome onRetryFailed(UUID subscriptionId, UUID invoiceId, DunningRetryState retryState, Instant failedAt,
                                       String correlationId);
}
