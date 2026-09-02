package com.subscriptionbilling.api.subscription;

import com.subscriptionbilling.api.error.CorrelationIds;
import com.subscriptionbilling.billingcore.subscription.SignupCommand;
import com.subscriptionbilling.billingcore.subscription.SubscriptionService;
import com.subscriptionbilling.billingcore.subscription.SubscriptionSignupResult;
import com.subscriptionbilling.billingcore.subscription.SubscriptionView;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Signup is this API's identity-bootstrap exception - reachable without a token — so a
 * caller's {@link Jwt} here is optional, present only when a re-subscribing Customer
 * supplied one; every other endpoint requires one (enforced by {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @Autowired
    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PostMapping
    public ResponseEntity<SignupResponse> signUp(@RequestBody @Valid SignupRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        UUID existingCustomerId = jwt != null ? UUID.fromString(jwt.getSubject()) : null;
        SignupCommand command = new SignupCommand(
                request.planId(), request.email(), existingCustomerId, request.useTrialOrDefault(),
                request.paymentMethodToken(), request.password(), CorrelationIds.current());
        SubscriptionSignupResult result = subscriptionService.signUp(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(SignupResponse.from(result));
    }

    @GetMapping("/{id}")
    public SubscriptionResponse getOwn(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID authenticatedCustomerId = UUID.fromString(jwt.getSubject());
        SubscriptionView view = subscriptionService.getOwnSubscription(id, authenticatedCustomerId);
        return SubscriptionResponse.from(view);
    }

    @PostMapping("/{id}/cancel")
    public SubscriptionResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID authenticatedCustomerId = UUID.fromString(jwt.getSubject());
        SubscriptionView view = subscriptionService.cancel(
                id, authenticatedCustomerId, idempotencyKey, CorrelationIds.current());
        return SubscriptionResponse.from(view);
    }

    @PostMapping("/{id}/undo-cancel")
    public SubscriptionResponse undoCancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID authenticatedCustomerId = UUID.fromString(jwt.getSubject());
        SubscriptionView view = subscriptionService.undoCancel(
                id, authenticatedCustomerId, idempotencyKey, CorrelationIds.current());
        return SubscriptionResponse.from(view);
    }

    @PostMapping("/{id}/plan-change")
    public SubscriptionResponse schedulePlanChange(@PathVariable UUID id, @RequestBody @Valid PlanChangeRequest request,
                                                     @AuthenticationPrincipal Jwt jwt,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID authenticatedCustomerId = UUID.fromString(jwt.getSubject());
        SubscriptionView view = subscriptionService.schedulePlanChange(
                id, authenticatedCustomerId, request.planId(), idempotencyKey, CorrelationIds.current());
        return SubscriptionResponse.from(view);
    }

    /**
     * Unlike this controller's other mutating endpoints, {@link
     * SubscriptionService#retryPayment} cannot itself return the resulting {@link
     * SubscriptionView} — it must not run inside a database transaction spanning the
     * synchronous gateway call, so a fresh, separately-transactional fetch is needed
     * once it returns.
     */
    @PostMapping("/{id}/retry-payment")
    public SubscriptionResponse retryPayment(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID authenticatedCustomerId = UUID.fromString(jwt.getSubject());
        subscriptionService.retryPayment(id, authenticatedCustomerId, idempotencyKey, CorrelationIds.current());
        SubscriptionView view = subscriptionService.getOwnSubscription(id, authenticatedCustomerId);
        return SubscriptionResponse.from(view);
    }
}
