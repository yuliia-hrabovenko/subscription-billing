package com.subscriptionbilling.api.plan;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** {@code POST /api/v1/admin/plans}'s request body: creates the Plan plus its first, immediately-effective PriceVersion. */
public record CreatePlanRequest(@NotBlank String code, @NotBlank String name,
                                 @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal initialPrice) {
}
