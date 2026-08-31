package com.subscriptionbilling.billingcore.auth;

import com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link AdminTokenIssuer} and {@link JwtConfiguration}'s decoder agree, and
 * that an Admin token carries {@code role=ADMIN} with the username (not a UUID) as
 * subject — mirrors {@link CustomerTokenIssuerIT}.
 */
@SpringBootTest
class AdminTokenIssuerIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private AdminTokenIssuer tokenIssuer;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void aMintedTokenDecodesBackToTheSameUsernameWithTheAdminRoleAndAFutureExpiry() {
        String token = tokenIssuer.issueFor("ops-admin");
        Jwt decoded = jwtDecoder.decode(token);

        assertThat(decoded.getSubject()).isEqualTo("ops-admin");
        assertThat(decoded.getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(decoded.getExpiresAt()).isAfter(Instant.now());
    }
}
