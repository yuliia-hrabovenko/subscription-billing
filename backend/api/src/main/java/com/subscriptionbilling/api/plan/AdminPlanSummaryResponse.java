package com.subscriptionbilling.api.plan;

import com.subscriptionbilling.billingcore.plan.AdminPlanSummary;

import java.math.BigDecimal;
import java.util.UUID;

/** {@code GET /api/v1/admin/plans}'s response shape — unlike {@link PlanResponse}, includes retired Plans. */
public record AdminPlanSummaryResponse(UUID id, String code, String name, boolean retiredForSignup, BigDecimal currentPrice) {

    static AdminPlanSummaryResponse from(AdminPlanSummary summary) {
        return new AdminPlanSummaryResponse(
                summary.id(), summary.code(), summary.name(), summary.retiredForSignup(), summary.currentPrice());
    }
}
