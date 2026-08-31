package com.subscriptionbilling.billingcore.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for this service's single configured Admin identity. No
 * {@code Admin} entity/table exists — v1's approved scope has no multi-admin-account
 * management, so a single configured username plus a bcrypt password hash is the
 * whole identity model, following the same "dev default in code, override via env var
 * in every real environment" convention {@link JwtProperties#getSecret()} already
 * establishes. A blank {@code passwordHash} (the default) means no real hash has been
 * configured; {@link AdminAuthenticationService} falls back to a dev-only password in
 * that case rather than accepting a blank hash as a valid credential.
 */
@ConfigurationProperties(prefix = "billing.security.admin")
public class AdminAuthProperties {

    private String username = "admin";
    private String passwordHash = "";

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
