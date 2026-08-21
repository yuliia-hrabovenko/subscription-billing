package com.subscriptionbilling.api.error;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Establishes a correlation ID for every request — reusing a client-supplied
 * {@code X-Correlation-Id} if present so a multi-hop caller can tie its own trace to
 * ours, otherwise minting one. Stored in MDC (not a request attribute) so it's available
 * both to {@link GlobalExceptionHandler} and to any structured log line emitted while
 * handling the request.
 *
 * <p>Ordered explicitly ahead of Spring Security's filter chain: a request Security
 * rejects (401/403) never reaches a controller, but its error response still needs a
 * correlationId, which requires MDC to already be populated when Security's entry
 * point/access-denied handler runs — not merely by the time a controller would have.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    private static final String HEADER = "X-Correlation-Id";

    @Override
    public int getOrder() {
        return SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(CorrelationIds.MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled and reused across requests: an MDC value left behind
            // would otherwise leak into whichever unrelated request runs on this thread next.
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }
}
