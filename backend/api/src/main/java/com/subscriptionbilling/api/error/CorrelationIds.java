package com.subscriptionbilling.api.error;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * The single source of truth for the correlation-id-with-fallback rule every error path
 * in this module needs: {@link GlobalExceptionHandler} (Spring MVC's own exception
 * resolution), and the Security-level {@code AuthenticationEntryPoint}/{@code
 * AccessDeniedHandler} (rejections that never reach MVC at all). All three must produce
 * the same structured error envelope, so they share this instead of each re-deriving
 * "fall back to a fresh ID when MDC has none." Also the one place outside this package
 * (e.g. a controller stamping an audit trail entry with the request's correlation id)
 * should reach for the current value, rather than reading MDC directly.
 */
public final class CorrelationIds {

    static final String MDC_KEY = "correlationId";

    private CorrelationIds() {
    }

    public static String current() {
        String id = MDC.get(MDC_KEY);
        // Falls back to a fresh ID rather than null/"unknown": every error response must
        // carry a correlationId, even where CorrelationIdFilter hasn't run yet/at all.
        return id != null ? id : UUID.randomUUID().toString();
    }
}
