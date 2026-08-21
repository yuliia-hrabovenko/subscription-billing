package com.subscriptionbilling.billingcore.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Wires the encoder and decoder for this service's self-issued Customer JWTs
 * from one shared secret. Both beans live here, in {@code billing-core}, rather than
 * split between the module that mints tokens ({@link CustomerTokenIssuer}, also here)
 * and {@code api}'s Resource Server config: an encoder and decoder that don't agree on
 * the signing key can't validate each other's tokens, so keeping them next to the same
 * {@link JwtProperties} instance is what guarantees they never drift apart.
 *
 * <p>A single symmetric (HS256) key is deliberate, not a placeholder for a future
 * upgrade: this service both issues and validates its own tokens (no external IdP, no
 * third party ever needs to verify one independently, so there's no
 * scenario an asymmetric keypair or a published JWKS endpoint would serve that a shared
 * secret doesn't already.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {

    @Bean
    public JwtEncoder jwtEncoder(JwtProperties properties) {
        JWK jwk = new OctetSequenceKey.Builder(signingKey(properties))
                .algorithm(JWSAlgorithm.HS256)
                .build();
        JWKSource<SecurityContext> jwkSource = (jwkSelector, context) -> jwkSelector.select(new JWKSet(jwk));
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties) {
        return NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    private SecretKey signingKey(JwtProperties properties) {
        return new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
