package com.subscriptionbilling.api.error;

/**
 * The structured error response shape — {@code {"error": {"code",
 * "message", "correlationId"}}} — used for every rejected request in this module.
 */
public record ApiError(ErrorDetail error) {

    public record ErrorDetail(String code, String message, String correlationId) {
    }
}
