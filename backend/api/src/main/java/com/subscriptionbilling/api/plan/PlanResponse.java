package com.subscriptionbilling.api.plan;

import com.subscriptionbilling.billingcore.plan.PlanSummary;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The public JSON shape for a Plan. Kept separate from {@link PlanSummary} (the
 * billing-core read model this is built from) so the wire contract can evolve
 * independently of that module's internal read model.
 */
public record PlanResponse(UUID id, String code, String name, BigDecimal price) {

    static PlanResponse from(PlanSummary summary) {
        return new PlanResponse(summary.id(), summary.code(), summary.name(), summary.currentPrice());
    }
}
