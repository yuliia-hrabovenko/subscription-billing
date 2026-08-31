package com.subscriptionbilling.billingcore.plan;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read model for the Admin plan catalog listing — unlike {@link
 * PlanSummary}, includes retired Plans and exposes {@code retiredForSignup}, since an
 * Admin needs full catalog visibility, not just what's open for new signups.
 * {@code currentPrice} is null for a Plan with no {@link PriceVersion} yet in effect.
 */
public record AdminPlanSummary(UUID id, String code, String name, boolean retiredForSignup, BigDecimal currentPrice) {
}
