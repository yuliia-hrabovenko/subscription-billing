package com.subscriptionbilling.api.paymentmethod;

import com.subscriptionbilling.payments.paymentmethod.SetupIntentPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backs signup's card-collection step, run before a Customer record (and therefore any
 * bearer token) exists — permitted unauthenticated, same as {@code POST
 * /api/v1/subscriptions} itself, per {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1/payment-methods")
public class SetupIntentController {

    private final SetupIntentPort setupIntentPort;

    @Autowired
    public SetupIntentController(SetupIntentPort setupIntentPort) {
        this.setupIntentPort = setupIntentPort;
    }

    @PostMapping("/setup-intent")
    public SetupIntentResponse createSetupIntent() {
        return new SetupIntentResponse(setupIntentPort.createClientSecret());
    }
}
