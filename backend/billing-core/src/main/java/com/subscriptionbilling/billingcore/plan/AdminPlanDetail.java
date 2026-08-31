package com.subscriptionbilling.billingcore.plan;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model for a single Plan's Admin detail view, including its full
 * {@link PriceVersion} history — every version ever created, not just the one
 * currently in effect, so an Admin can see the whole price timeline (past and
 * future-scheduled) they're managing.
 */
public record AdminPlanDetail(UUID id, String code, String name, boolean retiredForSignup,
                               List<PriceVersionEntry> priceHistory) {

    /** Oldest first is not guaranteed here — see {@link PlanAdministrationService} for ordering. */
    public record PriceVersionEntry(UUID id, BigDecimal amount, Instant effectiveFrom) {

        static PriceVersionEntry from(PriceVersion priceVersion) {
            return new PriceVersionEntry(priceVersion.getId(), priceVersion.getAmount(), priceVersion.getEffectiveFrom());
        }
    }
}
