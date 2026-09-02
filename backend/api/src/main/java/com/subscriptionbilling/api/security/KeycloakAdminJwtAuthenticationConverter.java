package com.subscriptionbilling.api.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Maps a Keycloak-issued access token to Spring Security authorities for the ADMIN
 * role (ADR-0009). Keycloak carries realm role membership under the nested {@code
 * realm_access.roles} claim, not the flat {@code role} claim {@link SecurityConfig}'s
 * self-issued-token converter reads — a separate converter, not a shared one, because
 * the two token shapes come from genuinely different issuers. A token missing the
 * configured admin role decodes to an authenticated principal with no authorities,
 * which {@code SecurityConfig}'s {@code .hasRole("ADMIN")} rule then rejects with 403
 * the same way it already rejects a Customer token on an Admin path.
 *
 * <p>The principal name comes from {@code preferred_username}, not {@code sub}
 * (Keycloak's {@code sub} is an opaque UUID) so anything downstream naming "which
 * Admin" acted (audit logging, in particular) sees a real identity, not an opaque id —
 * mirrors the deleted {@code AdminTokenIssuer}'s former use of the admin username as
 * subject.
 *
 * <p>Deliberately a plain object, not a Spring {@code @Component}: {@link
 * SecurityConfig} constructs it directly with the one configured value it needs (its
 * own {@code adminRole} {@code @Value}). Making it a component-scanned singleton would
 * force every application context — including web-layer slice tests that never
 * authenticate an Admin — to resolve it (and its dependencies) eagerly at startup.
 */
public class KeycloakAdminJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final String adminRole;

    public KeycloakAdminJwtAuthenticationConverter(String adminRole) {
        this.adminRole = adminRole;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = hasAdminRole(jwt)
                ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of();
        return new JwtAuthenticationToken(jwt, authorities, principalName(jwt));
    }

    private boolean hasAdminRole(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) {
            return false;
        }
        Object roles = realmAccess.get("roles");
        return roles instanceof List<?> roleList && roleList.contains(adminRole);
    }

    private String principalName(Jwt jwt) {
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        return StringUtils.hasText(preferredUsername) ? preferredUsername : jwt.getSubject();
    }
}
