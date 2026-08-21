package com.subscriptionbilling.api.error;

import com.subscriptionbilling.billingcore.plan.PlanUnavailableForSignupException;
import com.subscriptionbilling.billingcore.subscription.SubscriptionAccessDeniedException;
import com.subscriptionbilling.billingcore.subscription.UnsupportedSignupPlanException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Establishes the structured error response shape for every rejected
 * request in this module.
 *
 * <p>A domain-specific rejection (e.g. a future "subscription already canceled") isn't
 * one of Spring's built-ins, so it needs its own {@code @ExceptionHandler} in this class
 * (or a subclass) that calls {@link #respond}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String code = status != null ? status.name() : String.valueOf(statusCode.value());
        String message = status != null ? status.getReasonPhrase() : "Unexpected error";
        return ResponseEntity.status(statusCode).headers(headers).body(errorBody(code, message));
    }

    /**
     * Catches whatever isn't one of Spring MVC's recognized exceptions (an unexpected
     * runtime failure from a service, say) — {@link ResponseEntityExceptionHandler} has
     * no catch-all of its own, so without this, such an exception would fall through to
     * default {@code /error} page instead of this module's error shape.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception [correlationId={}]", CorrelationIds.current(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR.name(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
    }

    /**
     * ADR-0003: an unowned Subscription must fail with 403, never 404 — see {@link
     * SubscriptionAccessDeniedException}'s Javadoc for why not-found and not-owned are
     * the same outcome here.
     */
    @ExceptionHandler(SubscriptionAccessDeniedException.class)
    public ResponseEntity<Object> handleSubscriptionAccessDenied(SubscriptionAccessDeniedException ex) {
        return respond(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage());
    }

    @ExceptionHandler(PlanUnavailableForSignupException.class)
    public ResponseEntity<Object> handlePlanUnavailableForSignup(PlanUnavailableForSignupException ex) {
        return respond(HttpStatus.BAD_REQUEST, "PLAN_UNAVAILABLE_FOR_SIGNUP", ex.getMessage());
    }

    @ExceptionHandler(UnsupportedSignupPlanException.class)
    public ResponseEntity<Object> handleUnsupportedSignupPlan(UnsupportedSignupPlanException ex) {
        return respond(HttpStatus.BAD_REQUEST, "UNSUPPORTED_SIGNUP_PLAN", ex.getMessage());
    }

    /**
     * A DB-level constraint violation (e.g. signup racing a duplicate email, or a
     * customer identified by their own token re-subscribing while
     * partial-unique-index already has a non-canceled Subscription for them) must never
     * surface as a raw 500 — that leaks schema detail and violates this module's "every
     * rejected request gets the structured envelope" contract. This is a safety net, not
     * a substitute for the dedicated duplicate-signup: it
     * reports a generic conflict rather than distinguishing which constraint was hit.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation [correlationId={}]", CorrelationIds.current(), ex);
        return respond(HttpStatus.CONFLICT, "CONFLICT", "The request conflicts with existing data.");
    }

    private ResponseEntity<Object> respond(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(errorBody(code, message));
    }

    private ApiError errorBody(String code, String message) {
        return new ApiError(new ApiError.ErrorDetail(code, message, CorrelationIds.current()));
    }
}
