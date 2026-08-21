package com.subscriptionbilling.billingcore.plan;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read model for the Plan catalog. Other modules (e.g. {@code api}'s PlanController)
 * consume this instead of the {@link Plan}/{@link PriceVersion} entities directly —
 * cross-module JPA entity access is a module-boundary violation.
 */
public record PlanSummary(UUID id, String code, String name, BigDecimal currentPrice) {
}
