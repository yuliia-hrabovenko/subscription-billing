package com.subscriptionbilling.api.admin;

/** {@code POST /api/v1/admin/login}'s response: the bearer token for every subsequent {@code /api/v1/admin/**} request. */
public record AdminLoginResponse(String accessToken) {
}
