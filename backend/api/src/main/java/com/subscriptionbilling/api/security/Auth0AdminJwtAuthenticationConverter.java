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

/**
 * Converts Auth0 access-token claims into Spring Security authorities for ADMIN.
 * Auth0 roles are exposed by a Post-Login Action as a namespaced {@code groups} array.
 * Tokens without the configured admin group remain authenticated but have no authorities,
 * causing {@code .hasRole("ADMIN")} to return 403.
 *
 * <p>Uses {@code preferred_username} as the principal name, falling back to {@code sub},
 * so downstream audit logs identify the Admin by username rather than Auth0's opaque ID.
 *
 * <p>{@code claimNamespace} supports Auth0's OIDC-required namespaced claims while an
 * empty namespace allows bare claims used by the test mock provider.
 *
 * <p>Constructed directly by {@link SecurityConfig} rather than component-scanned to
 * avoid unnecessary initialization in contexts that never authenticate Admins.
 */
public class Auth0AdminJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final String adminRole;
    private final String claimNamespace;

    public Auth0AdminJwtAuthenticationConverter(String adminRole, String claimNamespace) {
        this.adminRole = adminRole;
        this.claimNamespace = claimNamespace;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = hasAdminRole(jwt)
                ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of();
        return new JwtAuthenticationToken(jwt, authorities, principalName(jwt));
    }

    private boolean hasAdminRole(Jwt jwt) {
        List<String> groups = jwt.getClaimAsStringList(claimKey("groups"));
        return groups != null && groups.contains(adminRole);
    }

    private String principalName(Jwt jwt) {
        String preferredUsername = jwt.getClaimAsString(claimKey("preferred_username"));
        return StringUtils.hasText(preferredUsername) ? preferredUsername : jwt.getSubject();
    }

    private String claimKey(String claimName) {
        return StringUtils.hasText(claimNamespace) ? claimNamespace + "/" + claimName : claimName;
    }
}
