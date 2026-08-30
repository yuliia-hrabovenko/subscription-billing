package com.subscriptionbilling.billingjob.anchor;

import com.subscriptionbilling.billingjob.ChargeTrigger;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The seam a due Subscription's successful-charge outcome is persisted through, so the
 * billing job drives {@code due_date} advancement — and, for a Trial's auto-conversion
 * charge or a Dunning retry's recovery, the resulting {@code -> active} transition and
 * (for a Trial) the first Billing Cycle it opens — without depending on billing-core's
 * Subscription persistence directly. Renewal, Trial-conversion, and Dunning-retry
 * charges call this the exact same way; an implementation decides whether a transition
 * applies from the Subscription's own persisted state, not from anything the caller
 * passes in. The billing job itself resolves {@code nextDueDate} via {@link
 * AnchorDate#next} before calling this — an implementation only persists the
 * already-resolved date (and, where applicable, the newly opened Anchor Date).
 */
public interface BillingCycleAdvancePort {

    /**
     * @param subscriptionId the Subscription just successfully charged
     * @param nextDueDate    the resolved next Billing Cycle date to advance {@code due_date} to
     * @param correlationId  the gateway's reference for the successful charge, carried onto
     *                       the {@code AuditLogEntry} when this charge also transitions the
     *                       Subscription's state
     * @param trigger        who this charge attempt was triggered by, so a resulting
     *                       {@code AuditLogEntry} attributes a Dunning retry's recovery
     *                       correctly whether it was scheduled or self-service
     */
    void advanceDueDate(UUID subscriptionId, LocalDate nextDueDate, String correlationId, ChargeTrigger trigger);
}
