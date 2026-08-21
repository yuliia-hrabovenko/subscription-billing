package com.subscriptionbilling.billingcore.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Signing material and claims for this service's self-issued Customer JWTs.
 * Carries a working default so every module's test context can boot a {@link
 * JwtConfiguration} with zero required configuration; {@code api}'s application.yml
 * overrides {@code secret} via the {@code JWT_SECRET} environment variable in real
 * environments, the same convention already used for this project's DB credentials.
 */
@ConfigurationProperties(prefix = "billing.security.jwt")
public class JwtProperties {

    private String secret = "dev-only-jwt-signing-secret-override-with-JWT_SECRET-env-var-in-every-real-environment";
    private String issuer = "subscription-billing";
    private Duration tokenTtl = Duration.ofHours(1);

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }
}
