package com.subscriptionbilling.api.admin;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/v1/admin/login}'s request body. */
public record AdminLoginRequest(@NotBlank String username, @NotBlank String password) {
}
