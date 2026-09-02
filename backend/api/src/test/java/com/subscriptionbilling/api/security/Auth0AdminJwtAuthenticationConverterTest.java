package com.subscriptionbilling.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Auth0AdminJwtAuthenticationConverterTest {

    private final Auth0AdminJwtAuthenticationConverter converter =
            new Auth0AdminJwtAuthenticationConverter("admin", "");

    @Test
    void aTokenWithTheAdminGroupGetsRoleAdminAndAHumanReadablePrincipalName() {
        Jwt jwt = jwtWithGroups("ops-admin", List.of("admin"));

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication.getName()).isEqualTo("ops-admin");
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
    }

    @Test
    void aTokenWithoutTheAdminGroupGetsNoAuthorities() {
        Jwt jwt = jwtWithGroups("regular-user", List.of("some-other-group"));

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication.getAuthorities()).isEmpty();
    }

    @Test
    void aTokenWithNoGroupsClaimAtAllGetsNoAuthorities() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "opaque-id")
                .claim("preferred_username", "no-groups-claim")
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
                .claim("sub", "opaque-id")
                .claim("groups", List.of("admin"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication.getName()).isEqualTo("opaque-id");
    }

    @Test
    void withAClaimNamespaceConfiguredOnlyTheNamespacedGroupsClaimIsHonoured() {
        Auth0AdminJwtAuthenticationConverter namespacedConverter =
                new Auth0AdminJwtAuthenticationConverter("admin", "https://api.subscription-billing.local");
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "opaque-id")
                .claim("preferred_username", "namespaced-admin")
                .claim("https://api.subscription-billing.local/preferred_username", "namespaced-admin")
                .claim("https://api.subscription-billing.local/groups", List.of("admin"))
                // A bare `groups` claim must be ignored once a namespace is configured -
                // otherwise an unrelated/attacker-controlled bare claim could grant ADMIN.
                .claim("groups", List.of("admin"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AbstractAuthenticationToken authentication = namespacedConverter.convert(jwt);

        assertThat(authentication.getName()).isEqualTo("namespaced-admin");
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
    }

    @Test
    void withAClaimNamespaceConfiguredABareGroupsClaimAloneGrantsNoAuthorities() {
        Auth0AdminJwtAuthenticationConverter namespacedConverter =
                new Auth0AdminJwtAuthenticationConverter("admin", "https://api.subscription-billing.local");
        Jwt jwt = jwtWithGroups("ops-admin", List.of("admin"));

        AbstractAuthenticationToken authentication = namespacedConverter.convert(jwt);

        assertThat(authentication.getAuthorities()).isEmpty();
    }

    private Jwt jwtWithGroups(String preferredUsername, List<String> groups) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "opaque-id")
                .claim("preferred_username", preferredUsername)
                .claim("groups", groups)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
