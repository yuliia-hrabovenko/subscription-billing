package com.subscriptionbilling.api.error;

import com.subscriptionbilling.billingcore.auth.CustomerAuthenticationException;
import com.subscriptionbilling.billingcore.customer.CustomerNotFoundException;
import com.subscriptionbilling.billingcore.customer.InvalidCustomerCursorException;
import com.subscriptionbilling.billingcore.plan.InvalidPriceVersionException;
import com.subscriptionbilling.billingcore.plan.PlanCodeAlreadyExistsException;
import com.subscriptionbilling.billingcore.plan.PlanNotFoundException;
import com.subscriptionbilling.billingcore.plan.PlanUnavailableForSignupException;
import com.subscriptionbilling.billingcore.subscription.DuplicateSubscriptionException;
import com.subscriptionbilling.billingcore.subscription.PaymentGatewayUnavailableException;
import com.subscriptionbilling.billingcore.subscription.PaymentMethodRequiredException;
import com.subscriptionbilling.billingcore.subscription.SubscriptionAccessDeniedException;
import com.subscriptionbilling.billingcore.subscription.SubscriptionAlreadyCanceledException;
import com.subscriptionbilling.billingcore.subscription.SubscriptionAlreadyPendingCancellationException;
import com.subscriptionbilling.billingcore.subscription.SubscriptionNotEligibleForPlanChangeException;
import com.subscriptionbilling.billingcore.subscription.SubscriptionNotFoundException;
import com.subscriptionbilling.billingcore.subscription.SubscriptionNotPendingCancellationException;
import com.subscriptionbilling.billingcore.subscription.SubscriptionNotSuspendedException;
import com.subscriptionbilling.invoicing.invoice.InvalidCursorException;
import com.subscriptionbilling.invoicing.invoice.InvoiceAccessDeniedException;
import com.subscriptionbilling.invoicing.invoice.InvoiceNotFoundException;
import com.subscriptionbilling.invoicing.receipt.ReceiptNotAvailableException;
import com.subscriptionbilling.payments.paymentmethod.PaymentMethodAttachmentException;
import com.subscriptionbilling.webhooks.ingestion.ConflictingPaymentOutcomeException;
import com.subscriptionbilling.webhooks.ingestion.InvalidWebhookSignatureException;
import com.subscriptionbilling.webhooks.ingestion.MalformedWebhookPayloadException;
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
     * ADR-0003: an unowned Invoice must fail with 403, never 404 — see {@link
     * InvoiceAccessDeniedException}'s Javadoc for why not-found and not-owned are the
     * same outcome here.
     */
    @ExceptionHandler(InvoiceAccessDeniedException.class)
    public ResponseEntity<Object> handleInvoiceAccessDenied(InvoiceAccessDeniedException ex) {
        return respond(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage());
    }

    /**
     * A malformed or tampered pagination cursor is a client input error, not a
     * not-found/not-owned Invoice — 400, not 403.
     */
    @ExceptionHandler(InvalidCursorException.class)
    public ResponseEntity<Object> handleInvalidCursor(InvalidCursorException ex) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", ex.getMessage());
    }

    /** Same reasoning as {@link #handleInvalidCursor}, for the Admin customer list endpoint. */
    @ExceptionHandler(InvalidCustomerCursorException.class)
    public ResponseEntity<Object> handleInvalidCustomerCursor(InvalidCustomerCursorException ex) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", ex.getMessage());
    }

    /**
     * Submitted customer login credentials didn't match — 401, the same status a
     * missing/invalid bearer token gets, since both mean "this caller isn't who it
     * claims to be." Admin sign-in has no password of its own to reject this way
     * anymore — a rejected Auth0 token is a resource-server concern,
     * handled by {@code ApiAuthenticationEntryPoint}/{@code ApiAccessDeniedHandler}.
     */
    @ExceptionHandler(CustomerAuthenticationException.class)
    public ResponseEntity<Object> handleLoginAuthentication(CustomerAuthenticationException ex) {
        return respond(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.getMessage());
    }

    /**
     * An Admin request targeted a Customer/Subscription/Invoice/Plan id that
     * doesn't exist. A plain 404, unlike the Customer-facing 403-not-404 conventions
     * above — an Admin already has full visibility, so there's no ID-enumeration
     * concern those exist to defend against.
     */
    @ExceptionHandler({CustomerNotFoundException.class, SubscriptionNotFoundException.class,
            InvoiceNotFoundException.class, PlanNotFoundException.class})
    public ResponseEntity<Object> handleAdminResourceNotFound(RuntimeException ex) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    /**
     * An Admin tried to create a Plan whose {@code code} is already in use —
     * same "dedicated 409 + integrity-violation safety net" pattern as {@link
     * #handleDuplicateSubscription}.
     */
    @ExceptionHandler(PlanCodeAlreadyExistsException.class)
    public ResponseEntity<Object> handlePlanCodeAlreadyExists(PlanCodeAlreadyExistsException ex) {
        return respond(HttpStatus.CONFLICT, "PLAN_CODE_ALREADY_EXISTS", ex.getMessage());
    }

    /**
     * An Admin's new PriceVersion doesn't come after the Plan's current
     * latest one — a malformed request, not a conflict with existing data.
     */
    @ExceptionHandler(InvalidPriceVersionException.class)
    public ResponseEntity<Object> handleInvalidPriceVersion(InvalidPriceVersionException ex) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_PRICE_VERSION", ex.getMessage());
    }

    /**
     * The caller is authorized for this Invoice but its receipt doesn't exist yet (or
     * ever will, for a failed Invoice) — a conflict with the Invoice's current state,
     * not a malformed request or an access rejection.
     */
    @ExceptionHandler(ReceiptNotAvailableException.class)
    public ResponseEntity<Object> handleReceiptNotAvailable(ReceiptNotAvailableException ex) {
        return respond(HttpStatus.CONFLICT, "RECEIPT_NOT_AVAILABLE", ex.getMessage());
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
     * The gateway rejected onboarding the card itself (network failure, malformed
     * response, or an outright attach rejection) — same upstream-failure framing as
     * {@link #handlePaymentGatewayUnavailable}, not a client-facing validation error.
     */
    @ExceptionHandler(PaymentMethodAttachmentException.class)
    public ResponseEntity<Object> handlePaymentMethodAttachmentFailed(PaymentMethodAttachmentException ex) {
        log.warn("Payment method attachment failed [correlationId={}]", CorrelationIds.current(), ex);
        return respond(HttpStatus.BAD_GATEWAY, "PAYMENT_METHOD_ATTACHMENT_FAILED", ex.getMessage());
    }

    /**
     * A gateway webhook request whose signature doesn't verify — never a customer
     * bearer-token rejection, but the same 401 status since both mean "this caller
     * isn't who it claims to be."
     */
    @ExceptionHandler(InvalidWebhookSignatureException.class)
    public ResponseEntity<Object> handleInvalidWebhookSignature(InvalidWebhookSignatureException ex) {
        log.warn("Webhook signature verification failed [correlationId={}]", CorrelationIds.current());
        return respond(HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_SIGNATURE", ex.getMessage());
    }

    /**
     * A signature-verified webhook payload that isn't valid JSON, is missing its event
     * id, or has an event type this system doesn't recognize — a malformed request,
     * not an authentication failure.
     */
    @ExceptionHandler(MalformedWebhookPayloadException.class)
    public ResponseEntity<Object> handleMalformedWebhookPayload(MalformedWebhookPayloadException ex) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_WEBHOOK_PAYLOAD", ex.getMessage());
    }

    /**
     * A payment-succeeded/failed webhook event reports an outcome that conflicts with
     * one already recorded for the same gateway reference — a gateway data
     * inconsistency worth surfacing distinctly, logged at WARN, never silently
     * overwritten.
     */
    @ExceptionHandler(ConflictingPaymentOutcomeException.class)
    public ResponseEntity<Object> handleConflictingPaymentOutcome(ConflictingPaymentOutcomeException ex) {
        log.warn("Conflicting payment outcome reported by webhook [correlationId={}]: {}",
                CorrelationIds.current(), ex.getMessage());
        return respond(HttpStatus.CONFLICT, "CONFLICTING_PAYMENT_OUTCOME", ex.getMessage());
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
