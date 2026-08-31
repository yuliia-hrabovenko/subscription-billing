package com.subscriptionbilling.api.plan;

import com.subscriptionbilling.billingcore.plan.AdminPlanDetail;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response shape for {@code GET /api/v1/admin/plans/{id}} and every Plan-catalog
 * mutation (create/retire/reprice) — includes the full PriceVersion
 * history, oldest first.
 */
public record AdminPlanDetailResponse(UUID id, String code, String name, boolean retiredForSignup,
                                       List<PriceVersionResponse> priceHistory) {

    public record PriceVersionResponse(UUID id, BigDecimal amount, Instant effectiveFrom) {

        static PriceVersionResponse from(AdminPlanDetail.PriceVersionEntry entry) {
            return new PriceVersionResponse(entry.id(), entry.amount(), entry.effectiveFrom());
        }
    }

    static AdminPlanDetailResponse from(AdminPlanDetail detail) {
        return new AdminPlanDetailResponse(detail.id(), detail.code(), detail.name(), detail.retiredForSignup(),
                detail.priceHistory().stream().map(PriceVersionResponse::from).toList());
    }
}
