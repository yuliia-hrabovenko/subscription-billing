package com.subscriptionbilling.billingjob.anchor;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The seam a due Subscription's next Billing Cycle date is persisted through after a
 * successful charge, so the billing job drives {@code due_date} advancement without
 * depending on billing-core's Subscription persistence directly. The billing job itself
 * resolves the next date via {@link AnchorDate#next} before calling this — an
 * implementation only persists the already-resolved date.
 */
public interface BillingCycleAdvancePort {

    /**
     * @param subscriptionId the Subscription just successfully charged
     * @param nextDueDate    the resolved next Billing Cycle date to advance {@code due_date} to
     */
    void advanceDueDate(UUID subscriptionId, LocalDate nextDueDate);
}
