package com.subscriptionbilling.api.admin;

import com.subscriptionbilling.billingcore.auth.AdminAuthenticationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity-bootstrap for the Admin role — the one {@code /api/v1/admin/**}
 * endpoint reachable pre-token, per {@code SecurityConfig}'s {@code permitAll} rule.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminLoginController {

    private final AdminAuthenticationService adminAuthenticationService;

    @Autowired
    public AdminLoginController(AdminAuthenticationService adminAuthenticationService) {
        this.adminAuthenticationService = adminAuthenticationService;
    }

    @PostMapping("/login")
    public AdminLoginResponse login(@RequestBody @Valid AdminLoginRequest request) {
        String accessToken = adminAuthenticationService.authenticate(request.username(), request.password());
        return new AdminLoginResponse(accessToken);
    }
}
