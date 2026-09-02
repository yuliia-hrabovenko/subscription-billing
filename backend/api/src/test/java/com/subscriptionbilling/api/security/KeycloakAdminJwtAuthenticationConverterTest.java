package com.subscriptionbilling.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakAdminJwtAuthenticationConverterTest {

    private final KeycloakAdminJwtAuthenticationConverter converter =
            new KeycloakAdminJwtAuthenticationConverter("admin");

    @Test
    void aTokenWithTheAdminRealmRoleGetsRoleAdminAndAHumanReadablePrincipalName() {
        Jwt jwt = jwtWithRealmRoles("ops-admin", List.of("admin"));

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication.getName()).isEqualTo("ops-admin");
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
    }

    @Test
    void aTokenWithoutTheAdminRealmRoleGetsNoAuthorities() {
        Jwt jwt = jwtWithRealmRoles("regular-user", List.of("some-other-role"));

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication.getAuthorities()).isEmpty();
    }

    @Test
    void aTokenWithNoRealmAccessClaimAtAllGetsNoAuthorities() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "opaque-uuid")
                .claim("preferred_username", "no-realm-access")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication.getAuthorities()).isEmpty();
    }

    @Test
    void fallsBackToSubWhenPreferredUsernameIsMissing() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "opaque-uuid")
                .claim("realm_access", Map.of("roles", List.of("admin")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication.getName()).isEqualTo("opaque-uuid");
    }

    private Jwt jwtWithRealmRoles(String preferredUsername, List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "opaque-uuid")
                .claim("preferred_username", preferredUsername)
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
