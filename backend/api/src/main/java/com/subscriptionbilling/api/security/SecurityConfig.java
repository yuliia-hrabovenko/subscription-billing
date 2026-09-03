package com.subscriptionbilling.api.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Map;

/**
 * Configures stateless Bearer-JWT authentication with separate CUSTOMER and ADMIN
 * roles. CUSTOMER uses self-issued tokens validated with the service's symmetric key;
 * ADMIN uses Auth0-issued tokens (Authorization Code + PKCE) validated via Auth0 JWKS.
 *
 * <p>{@link #adminAuthenticationManagerResolver} selects the appropriate decoder and
 * converter from the token's {@code iss} claim. Both decoders use {@link
 * SupplierJwtDecoder} to defer decoder creation and Auth0 discovery/JWKS network calls
 * until an authenticated request requires them.
 *
 * <p>Public endpoints are signup/login, plan listing, gateway webhooks (authenticated
 * by gateway signature), and Prometheus scraping. All other endpoints require the role
 * associated with their path; CUSTOMER and ADMIN are mutually exclusive.
 *
 * <p>There is no Admin login endpoint: the SPA obtains Admin tokens directly from Auth0.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ApiAuthenticationEntryPoint authenticationEntryPoint;
    private final ApiAccessDeniedHandler accessDeniedHandler;
    private final ObjectProvider<JwtDecoder> customerJwtDecoderProvider;
    private final String customerIssuer;
    private final String adminIssuerUri;
    private final String adminRole;
    private final String adminClaimNamespace;
    private final List<String> allowedOrigins;
    private final List<String> allowedMethods;
    private final List<String> allowedHeaders;

    @Autowired
    public SecurityConfig(ApiAuthenticationEntryPoint authenticationEntryPoint,
                           ApiAccessDeniedHandler accessDeniedHandler,
                           ObjectProvider<JwtDecoder> customerJwtDecoderProvider,
                           @Value("${billing.security.jwt.issuer:subscription-billing}") String customerIssuer,
                           @Value("${billing.security.admin-sso.issuer-uri:"
                                   + "https://changeme.auth0.com/}") String adminIssuerUri,
                           @Value("${billing.security.admin-sso.admin-role:admin}") String adminRole,
                           @Value("${billing.security.admin-sso.claim-namespace:}") String adminClaimNamespace,
                           @Value("${app.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins,
                           @Value("${app.cors.allowed-methods:GET,POST,OPTIONS}") List<String> allowedMethods,
                           @Value("${app.cors.allowed-headers:Authorization,Content-Type,Idempotency-Key}")
                           List<String> allowedHeaders) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.customerJwtDecoderProvider = customerJwtDecoderProvider;
        this.customerIssuer = customerIssuer;
        this.adminIssuerUri = adminIssuerUri;
        this.adminRole = adminRole;
        this.adminClaimNamespace = adminClaimNamespace;
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
                        .requestMatchers(HttpMethod.POST, "/api/v1/payment-methods/setup-intent").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/customers/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/gateway").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
                        // Must come before the generic anyRequest() rule below — first
                        // match wins, and /api/v1/admin/** would otherwise fall through
                        // to the CUSTOMER-only default.
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().hasRole("CUSTOMER"))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationManagerResolver(adminAuthenticationManagerResolver())
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * Two issuers, two decoder/converter pairs, routed by the incoming token's (not yet
     * trusted — only used to pick a decoder, which then verifies the signature) {@code
     * iss} claim: this service's own issuer for self-issued CUSTOMER tokens
     * and Auth0's authorization-server issuer for ADMIN tokens
     * {@code JwtIssuerAuthenticationManagerResolver} only takes an
     * {@link AuthenticationManagerResolver} (no {@code Map} constructor), so the lookup
     * map's {@code get} method reference stands in as one.
     */
    private JwtIssuerAuthenticationManagerResolver adminAuthenticationManagerResolver() {
        JwtDecoder lazyCustomerDecoder = new SupplierJwtDecoder(customerJwtDecoderProvider::getObject);
        AuthenticationManager customerManager = new ProviderManager(
                jwtAuthenticationProvider(lazyCustomerDecoder, customerJwtAuthenticationConverter()));

        JwtDecoder lazyAdminDecoder = new SupplierJwtDecoder(() -> JwtDecoders.fromIssuerLocation(adminIssuerUri));
        AuthenticationManager adminManager = new ProviderManager(
                jwtAuthenticationProvider(lazyAdminDecoder,
                        new Auth0AdminJwtAuthenticationConverter(adminRole, adminClaimNamespace)));

        AuthenticationManagerResolver<String> byIssuer = Map.of(
                customerIssuer, customerManager,
                adminIssuerUri, adminManager)::get;
        return new JwtIssuerAuthenticationManagerResolver(byIssuer);
    }

    private JwtAuthenticationProvider jwtAuthenticationProvider(
            JwtDecoder decoder, Converter<Jwt, ? extends AbstractAuthenticationToken> converter) {
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);
        return provider;
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
     * This service's self-issued CUSTOMER tokens carry a single {@code role} claim
     * rather than the OAuth2-standard {@code scope}/{@code scp} claims the default
     * converter expects — those model a third-party-issued token's delegated scopes, a
     * concept this self-issued-token design has no use for. Auth0-issued ADMIN
     * tokens use a separate converter ({@link Auth0AdminJwtAuthenticationConverter})
     * that reads Auth0's own group-membership claim shape instead.
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
