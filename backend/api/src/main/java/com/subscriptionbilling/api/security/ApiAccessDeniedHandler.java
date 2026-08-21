package com.subscriptionbilling.api.security;

import com.subscriptionbilling.api.error.ApiErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * The 403 for a validly-authenticated caller lacking the {@code CUSTOMER} role — a
 * different rejection from {@link com.subscriptionbilling.billingcore.subscription.
 * SubscriptionAccessDeniedException}'s 403 (a valid CUSTOMER token whose owner just
 * isn't this resource's owner), but the client sees the same structured envelope either
 * way.
 */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiErrorResponseWriter responseWriter;

    @Autowired
    public ApiAccessDeniedHandler(ApiErrorResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                        AccessDeniedException accessDeniedException) throws IOException {
        responseWriter.write(response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have access to this resource.");
    }
}
