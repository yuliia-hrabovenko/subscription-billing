package com.subscriptionbilling.billingcore.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The one {@link PasswordEncoder} bean this service needs, for {@link
 * AdminAuthenticationService}'s credential check. No other identity in this
 * system has a password — Customers authenticate via self-issued bearer tokens only
 * (ADR-0003), never a credential this encoder would apply to.
 */
@Configuration
@EnableConfigurationProperties(AdminAuthProperties.class)
public class PasswordEncoderConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
