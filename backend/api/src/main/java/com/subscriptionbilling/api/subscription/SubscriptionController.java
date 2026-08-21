package com.subscriptionbilling.api.subscription;

import com.subscriptionbilling.api.error.CorrelationIds;
import com.subscriptionbilling.billingcore.subscription.FreeSignupCommand;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Signup and self-fetch. Signup is this API's identity-bootstrap exception -
 * reachable without a token — so a caller's {@link Jwt} here is optional,
 * present only when a re-subscribing Customer supplied one; the fetch endpoint requires
 * one (enforced by {@code SecurityConfig}), so its {@link Jwt} is never null by the time
 * this controller runs.
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
        FreeSignupCommand command = new FreeSignupCommand(
                request.planId(), request.email(), existingCustomerId, CorrelationIds.current());
        SubscriptionSignupResult result = subscriptionService.signUpForFreePlan(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(SignupResponse.from(result));
    }

    @GetMapping("/{id}")
    public SubscriptionResponse getOwn(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID authenticatedCustomerId = UUID.fromString(jwt.getSubject());
        SubscriptionView view = subscriptionService.getOwnSubscription(id, authenticatedCustomerId);
        return SubscriptionResponse.from(view);
    }
}
