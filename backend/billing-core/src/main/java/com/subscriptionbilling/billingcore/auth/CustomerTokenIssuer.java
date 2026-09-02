package com.subscriptionbilling.billingcore.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Mints a bearer token identifying a Customer, per ADR-0003's self-issued-JWT decision.
 * Used by the signup flow to hand a brand-new Customer their first token, and available
 * as a plain Spring bean for any later ticket/test that needs to act as an already-known
 * Customer (e.g. to exercise an ownership check) without re-deriving the signing setup.
 *
 * <p>The single {@code role} claim is deliberate, not a placeholder for a richer claims
 * model: every Customer-side authorization decision beyond that role is a
 * service-layer ownership check, not a token claim. A second role value ({@code ADMIN})
 * also exists, but is Keycloak-issued rather than self-issued and carries no
 * ownership semantics at all — an Admin isn't scoped to "their own" data the way a
 * Customer is.
 */
@Component
public class CustomerTokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    @Autowired
    public CustomerTokenIssuer(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public String issueFor(UUID customerId) {
        Instant now = Instant.now();
        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .issuedAt(now)
                .expiresAt(now.plus(properties.getTokenTtl()))
                .subject(customerId.toString())
                .claim("role", "CUSTOMER")
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }
}
