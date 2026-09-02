package com.subscriptionbilling.api.customer;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/v1/customers/login}'s request body. */
public record CustomerLoginRequest(@NotBlank String email, @NotBlank String password) {
}
