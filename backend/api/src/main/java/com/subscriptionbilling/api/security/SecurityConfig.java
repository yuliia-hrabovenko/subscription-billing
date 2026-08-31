package com.subscriptionbilling.api.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Bearer-JWT authentication via Spring Security's
 * OAuth2 Resource Server support, no session state. Two roles exist: {@code CUSTOMER}
 * for self-service endpoints, and {@code ADMIN} for the read-only/plan-catalog
 * back-office endpoints under {@code /api/v1/admin/**} — the two are mutually
 * exclusive by path, never both accepted on the same endpoint. {@code POST
 * /subscriptions} (signup), {@code GET /plans}, {@code POST /webhooks/gateway}, {@code
 * POST /admin/login}, and the Prometheus scrape endpoint are this API's only pre-auth
 * endpoints; every other request must carry a valid token for the role its path
 * requires. Anything beyond the role check (i.e. resource ownership, {@code CUSTOMER}
 * only — is a service-layer concern, not this filter chain's. Prometheus
 * (docker-compose) has no bearer token to present, so its scrape target can't sit
 * behind the same JWT requirement as customer/admin endpoints; the gateway webhook
 * endpoint has no customer identity to present at all, and is authenticated instead by
 * gateway signature verification inside the controller/service layer, not this filter
 * chain.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ApiAuthenticationEntryPoint authenticationEntryPoint;
    private final ApiAccessDeniedHandler accessDeniedHandler;
    private final List<String> allowedOrigins;
    private final List<String> allowedMethods;
    private final List<String> allowedHeaders;

    @Autowired
    public SecurityConfig(ApiAuthenticationEntryPoint authenticationEntryPoint,
                           ApiAccessDeniedHandler accessDeniedHandler,
                           @Value("${app.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins,
                           @Value("${app.cors.allowed-methods:GET,POST,OPTIONS}") List<String> allowedMethods,
                           @Value("${app.cors.allowed-headers:Authorization,Content-Type,Idempotency-Key}")
                           List<String> allowedHeaders) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.allowedOrigins = allowedOrigins;
        this.allowedMethods = allowedMethods;
        this.allowedHeaders = allowedHeaders;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // A stateless bearer-token API carries no ambient credential (no
                // session cookie) for a forged cross-site request to ride along on, so
                // CSRF protection defends against an attack this API isn't exposed to.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/v1/plans").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/subscriptions").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/gateway").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
                        // Must come before the generic anyRequest() rule below — first
                        // match wins, and /api/v1/admin/** would otherwise fall through
                        // to the CUSTOMER-only default.
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().hasRole("CUSTOMER"))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
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
     * The frontend is a separate origin (Vite dev server, or a deployed static host)
     * with no session cookie to ride along on, so this is a plain cross-origin API
     * call, not a CSRF-relevant one. All three lists are configurable per environment
     * ({@code app.cors.allowed-origins}/{@code -methods}/{@code -headers}) rather than
     * only origins, so adding a method or header this API doesn't use yet (e.g. once a
     * PATCH/DELETE endpoint exists) is a config change, not a code change.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(allowedMethods);
        configuration.setAllowedHeaders(allowedHeaders);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * This service's tokens (both {@code CUSTOMER} and {@code ADMIN}) carry
     * a single {@code role} claim rather than the OAuth2-standard {@code scope}/{@code
     * scp} claims the default converter expects — those model a third-party-issued
     * token's delegated scopes, a concept this self-issued-token design has no use for.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthorityPrefix("ROLE_");
        authoritiesConverter.setAuthoritiesClaimName("role");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }
}
