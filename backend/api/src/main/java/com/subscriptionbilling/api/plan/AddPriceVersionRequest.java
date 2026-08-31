package com.subscriptionbilling.api.plan;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code POST /api/v1/admin/plans/{id}/price-versions}'s request body.
 * {@code effectiveFrom} must come after the Plan's current latest PriceVersion — see
 * {@code InvalidPriceVersionException}.
 */
public record AddPriceVersionRequest(@NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal amount,
                                      @NotNull Instant effectiveFrom) {
}
