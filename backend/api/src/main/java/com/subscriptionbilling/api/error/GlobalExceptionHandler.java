package com.subscriptionbilling.api.error;

import com.subscriptionbilling.billingcore.plan.PlanUnavailableForSignupException;
import com.subscriptionbilling.billingcore.subscription.DuplicateSubscriptionException;
import com.subscriptionbilling.billingcore.subscription.PaymentGatewayUnavailableException;
import com.subscriptionbilling.billingcore.subscription.PaymentMethodRequiredException;
import com.subscriptionbilling.billingcore.subscription.SubscriptionAccessDeniedException;
import com.subscriptionbilling.billingcore.subscription.SubscriptionAlreadyCanceledException;
import com.subscriptionbilling.billingcore.subscription.SubscriptionAlreadyPendingCancellationException;
import com.subscriptionbilling.billingcore.subscription.SubscriptionNotEligibleForPlanChangeException;
import com.subscriptionbilling.billingcore.subscription.SubscriptionNotPendingCancellationException;
import com.subscriptionbilling.billingcore.subscription.SubscriptionNotSuspendedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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

    /**
     * A retired (or otherwise unselectable) Plan is a conflict with the Plan's current
     * state, not a malformed request — 409, consistent with {@link
     * #handleDuplicateSubscription} below.
     */
    @ExceptionHandler(PlanUnavailableForSignupException.class)
    public ResponseEntity<Object> handlePlanUnavailableForSignup(PlanUnavailableForSignupException ex) {
        return respond(HttpStatus.CONFLICT, "PLAN_UNAVAILABLE_FOR_SIGNUP", ex.getMessage());
    }

    /**
     * A dedicated 409, distinct from the generic {@link #handleDataIntegrityViolation}
     * fallback below, which exists only for the race this application-layer check
     * can't catch.
     */
    @ExceptionHandler(DuplicateSubscriptionException.class)
    public ResponseEntity<Object> handleDuplicateSubscription(DuplicateSubscriptionException ex) {
        return respond(HttpStatus.CONFLICT, "DUPLICATE_SUBSCRIPTION", ex.getMessage());
    }

    @ExceptionHandler(PaymentMethodRequiredException.class)
    public ResponseEntity<Object> handlePaymentMethodRequired(PaymentMethodRequiredException ex) {
        return respond(HttpStatus.BAD_REQUEST, "PAYMENT_METHOD_REQUIRED", ex.getMessage());
    }

    @ExceptionHandler(SubscriptionAlreadyCanceledException.class)
    public ResponseEntity<Object> handleSubscriptionAlreadyCanceled(SubscriptionAlreadyCanceledException ex) {
        return respond(HttpStatus.CONFLICT, "SUBSCRIPTION_ALREADY_CANCELED", ex.getMessage());
    }

    @ExceptionHandler(SubscriptionAlreadyPendingCancellationException.class)
    public ResponseEntity<Object> handleSubscriptionAlreadyPendingCancellation(
            SubscriptionAlreadyPendingCancellationException ex) {
        return respond(HttpStatus.CONFLICT, "SUBSCRIPTION_ALREADY_PENDING_CANCELLATION", ex.getMessage());
    }

    @ExceptionHandler(SubscriptionNotPendingCancellationException.class)
    public ResponseEntity<Object> handleSubscriptionNotPendingCancellation(
            SubscriptionNotPendingCancellationException ex) {
        return respond(HttpStatus.CONFLICT, "SUBSCRIPTION_NOT_PENDING_CANCELLATION", ex.getMessage());
    }

    @ExceptionHandler(SubscriptionNotEligibleForPlanChangeException.class)
    public ResponseEntity<Object> handleSubscriptionNotEligibleForPlanChange(
            SubscriptionNotEligibleForPlanChangeException ex) {
        return respond(HttpStatus.CONFLICT, "SUBSCRIPTION_NOT_ELIGIBLE_FOR_PLAN_CHANGE", ex.getMessage());
    }

    /**
     * 403, not 409 like the other "not eligible" rejections above: a self-service
     * payment retry is a per-request capability check ("are you the suspended
     * subscriber this applies to"), not a conflict with the target resource's own state.
     */
    @ExceptionHandler(SubscriptionNotSuspendedException.class)
    public ResponseEntity<Object> handleSubscriptionNotSuspended(SubscriptionNotSuspendedException ex) {
        return respond(HttpStatus.FORBIDDEN, "SUBSCRIPTION_NOT_SUSPENDED", ex.getMessage());
    }

    /**
     * The gateway itself failed transiently, not the request — safe to retry, so this
     * is reported as an upstream failure rather than a client-facing rejection.
     */
    @ExceptionHandler(PaymentGatewayUnavailableException.class)
    public ResponseEntity<Object> handlePaymentGatewayUnavailable(PaymentGatewayUnavailableException ex) {
        log.warn("Payment gateway unavailable [correlationId={}]", CorrelationIds.current(), ex);
        return respond(HttpStatus.BAD_GATEWAY, "PAYMENT_GATEWAY_UNAVAILABLE", ex.getMessage());
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

    /**
     * Two concurrent requests without a shared {@code Idempotency-Key} raced to commit
     * a transition; {@code Subscription}'s {@code @Version} field detects the loser at
     * commit time. A transient conflict, not a permanent rejection — a plain retry
     * resolves it.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
        log.warn("Optimistic locking failure [correlationId={}]", CorrelationIds.current(), ex);
        return respond(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "This subscription was modified concurrently by another request. Please retry.");
    }

    private ResponseEntity<Object> respond(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(errorBody(code, message));
    }

    private ApiError errorBody(String code, String message) {
        return new ApiError(new ApiError.ErrorDetail(code, message, CorrelationIds.current()));
    }
}
