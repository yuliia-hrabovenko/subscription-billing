package com.subscriptionbilling.api.customer;

import com.subscriptionbilling.billingcore.auth.CustomerAuthenticationService;
import com.subscriptionbilling.billingcore.auth.CustomerLoginResult;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity-bootstrap for a returning Customer — the second pre-token endpoint besides
 * signup ({@code POST /api/v1/subscriptions}), per {@code SecurityConfig}'s {@code
 * permitAll} rule.
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerLoginController {

    private final CustomerAuthenticationService customerAuthenticationService;

    @Autowired
    public CustomerLoginController(CustomerAuthenticationService customerAuthenticationService) {
        this.customerAuthenticationService = customerAuthenticationService;
    }

    @PostMapping("/login")
    public CustomerLoginResponse login(@RequestBody @Valid CustomerLoginRequest request) {
        CustomerLoginResult result = customerAuthenticationService.authenticate(request.email(), request.password());
        return CustomerLoginResponse.from(result);
    }
}
