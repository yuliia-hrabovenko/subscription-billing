package com.subscriptionbilling.billingcore.plan;

import java.time.Instant;
import java.util.UUID;

/**
 * An Admin ({@code POST /api/v1/admin/plans/{id}/price-versions}, submitted
 * a new {@link PriceVersion} whose {@code effectiveFrom} doesn't come strictly after
 * the Plan's current latest {@link PriceVersion}. Rejected rather than accepted
 * out-of-order: Price Version history is meant to stay
 * monotonic — "a later price change never retroactively alters a past invoice" only
 * holds if every new entry is actually later than the one before it.
 */
public class InvalidPriceVersionException extends RuntimeException {

    public InvalidPriceVersionException(UUID planId, Instant effectiveFrom, Instant mustBeAfter) {
        super("Plan " + planId + "'s new price version must have effectiveFrom after " + mustBeAfter
                + ", got " + effectiveFrom);
    }
}
