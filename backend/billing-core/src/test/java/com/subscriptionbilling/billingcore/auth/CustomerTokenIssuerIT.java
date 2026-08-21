package com.subscriptionbilling.billingcore.auth;

import com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the encoder ({@link CustomerTokenIssuer}) and decoder ({@link JwtConfiguration})
 * actually agree: a token minted here must be independently verifiable, since that's the
 * whole point of a self-issued, self-validated token.
 *
 * <p>Extends the Postgres base like every other {@code @SpringBootTest} in this module
 * even though this test never touches the database: booting the full context here
 * always brings up JPA/Flyway alongside it, token issuance or not.
 */
@SpringBootTest
class CustomerTokenIssuerIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private CustomerTokenIssuer tokenIssuer;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void aMintedTokenDecodesBackToTheSameCustomerWithTheCustomerRoleAndAFutureExpiry() {
        UUID customerId = UUID.randomUUID();

        String token = tokenIssuer.issueFor(customerId);
        Jwt decoded = jwtDecoder.decode(token);

        assertThat(decoded.getSubject()).isEqualTo(customerId.toString());
        assertThat(decoded.getClaimAsString("role")).isEqualTo("CUSTOMER");
        assertThat(decoded.getExpiresAt()).isAfter(Instant.now());
    }
}
