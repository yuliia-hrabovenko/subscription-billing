package com.subscriptionbilling.api.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * ADR-0003: bearer-JWT authentication via Spring Security's OAuth2 Resource Server
 * support, one {@code CUSTOMER} role, no session state. {@code POST /subscriptions}
 * (signup), {@code GET /plans}, {@code POST /webhooks/gateway}, and the Prometheus
 * scrape endpoint are this API's only pre-auth endpoints — every other request must
 * carry a valid {@code CUSTOMER} token; anything beyond the role check (i.e. resource
 * ownership) is a service-layer concern, not this filter chain's. Prometheus
 * (docker-compose) has no bearer token to present, so its scrape target can't sit
 * behind the same JWT requirement as customer endpoints; the gateway webhook endpoint
 * has no customer identity to present at all, and is authenticated instead by gateway
 * signature verification inside the controller/service layer, not this filter chain.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ApiAuthenticationEntryPoint authenticationEntryPoint;
    private final ApiAccessDeniedHandler accessDeniedHandler;

    @Autowired
    public SecurityConfig(ApiAuthenticationEntryPoint authenticationEntryPoint,
                           ApiAccessDeniedHandler accessDeniedHandler) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // A stateless bearer-token API carries no ambient credential (no
                // session cookie) for a forged cross-site request to ride along on, so
                // CSRF protection defends against an attack this API isn't exposed to.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/v1/plans").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/subscriptions").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/gateway").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
                        .anyRequest().hasRole("CUSTOMER"))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(customerJwtAuthenticationConverter()))
                        // The resource server filter handles a *presented* invalid/expired
                        // token itself, before authorizeHttpRequests' permitAll or the
                        // generic exceptionHandling() below ever run — without registering
                        // the same entry point/handler here too, that path falls back to
                        // Spring Security's own bare, bodyless default and breaks this
                        // module's "every rejection gets the structured envelope" contract.
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * This service's tokens carry a single {@code role} claim (ADR-0003: one CUSTOMER
     * role, no admin role in v1) rather than the OAuth2-standard {@code scope}/{@code
     * scp} claims the default converter expects — those model a third-party-issued
     * token's delegated scopes, a concept this self-issued-token design has no use for.
     */
    private JwtAuthenticationConverter customerJwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthorityPrefix("ROLE_");
        authoritiesConverter.setAuthoritiesClaimName("role");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }
}
