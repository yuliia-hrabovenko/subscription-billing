package com.subscriptionbilling.api.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.UUID;

/**
 * Establishes the structured error response shape for every rejected
 * request in this module.
 *
 * <p>A domain-specific rejection (e.g. a future "subscription already canceled") isn't
 * one of Spring's built-ins, so it needs its own {@code @ExceptionHandler} in this class
 * (or a subclass) that calls {@link #errorBody}/{@link #currentCorrelationId}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        return ResponseEntity.status(statusCode).headers(headers).body(errorBody(statusCode));
    }

    /**
     * Catches whatever isn't one of Spring MVC's recognized exceptions (an unexpected
     * runtime failure from a service, say) — {@link ResponseEntityExceptionHandler} has
     * no catch-all of its own, so without this, such an exception would fall through to
     * default {@code /error} page instead of this module's error shape.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception [correlationId={}]", currentCorrelationId(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    private ApiError errorBody(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String code = status != null ? status.name() : String.valueOf(statusCode.value());
        String message = status != null ? status.getReasonPhrase() : "Unexpected error";
        return new ApiError(new ApiError.ErrorDetail(code, message, currentCorrelationId()));
    }

    private String currentCorrelationId() {
        String id = MDC.get(CorrelationIdFilter.MDC_KEY);
        // Falls back to a fresh ID rather than null/"unknown": every error response must
        // carry a correlationId, even on the dispatch types CorrelationIdFilter
        // (a request-scoped servlet Filter) doesn't run for, e.g. an ERROR-dispatch retry.
        return id != null ? id : UUID.randomUUID().toString();
    }
}
