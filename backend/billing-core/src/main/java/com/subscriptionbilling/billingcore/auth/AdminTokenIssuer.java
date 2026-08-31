package com.subscriptionbilling.billingcore.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Mints a bearer token identifying the Admin — the same self-issued-JWT
 * machinery {@link CustomerTokenIssuer} uses, sharing this module's one {@link
 * JwtEncoder}/{@link JwtProperties}, but with {@code role=ADMIN} and the admin
 * username (not a Customer id) as the subject. {@link
 * com.subscriptionbilling.api.security.SecurityConfig}'s JWT authorities converter
 * reads the {@code role} claim generically, so no separate verification path is
 * needed for this second role value.
 */
@Component
public class AdminTokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    @Autowired
    public AdminTokenIssuer(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public String issueFor(String adminUsername) {
        Instant now = Instant.now();
        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .issuedAt(now)
                .expiresAt(now.plus(properties.getTokenTtl()))
                .subject(adminUsername)
                .claim("role", "ADMIN")
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }
}
