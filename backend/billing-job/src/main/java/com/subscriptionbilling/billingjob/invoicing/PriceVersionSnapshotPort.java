package com.subscriptionbilling.billingjob.invoicing;

import java.util.UUID;

/**
 * The seam a receipt is described through, so the invoicing module never depends on
 * billing-core's Plan/PriceVersion persistence directly. Resolves by the exact
 * PriceVersion id an Invoice snapshotted at charge time, never by "the Plan's current
 * price" (Invariant 9) — a later PriceVersion added for the same Plan must never change
 * what this returns for an id already charged.
 */
public interface PriceVersionSnapshotPort {

    /**
     * @param priceVersionId the PriceVersion id an Invoice snapshotted at charge time
     * @return the Plan name and amount fixed on that PriceVersion
     */
    PriceVersionSnapshot describe(UUID priceVersionId);
}
