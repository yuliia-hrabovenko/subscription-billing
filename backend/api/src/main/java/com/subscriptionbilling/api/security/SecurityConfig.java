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
 * Bearer-JWT authentication via Spring Security's OAuth2 Resource Server
 * support, no session state. Two roles exist: {@code CUSTOMER} for self-service
 * endpoints, and {@code ADMIN} for the read-only/plan-catalog back-office endpoints
 * under {@code /api/v1/admin/**} — the two are mutually exclusive by path, never both
 * accepted on the same endpoint. {@code POST /subscriptions} (signup), {@code POST
 * /customers/login}, {@code GET /plans}, {@code POST /webhooks/gateway}, and the
 * Prometheus scrape endpoint are this API's only pre-auth endpoints; every other
 * request must carry a valid token for the role its path requires. Prometheus
 * (docker-compose) has no bearer token to present, so its scrape target can't sit
 * behind the same JWT requirement as customer/admin endpoints; the gateway webhook
 * endpoint has no customer identity to present at all, and is authenticated instead by
 * gateway signature verification inside the controller/service layer, not this filter
 * chain.
 *
 * <p>ADR-0009: CUSTOMER and ADMIN tokens now come from two different issuers — CUSTOMER
 * stays a self-issued token (ADR-0003) validated by this service's own symmetric key;
 * ADMIN is a Keycloak-issued token (Authorization Code + PKCE, obtained by the SPA
 * directly from Keycloak — this backend never sees a client secret or mints an Admin
 * token itself), validated against Keycloak's published JWKS. There is no {@code POST
 * /admin/login} anymore; an Admin's only way to a token is through Keycloak.
 * {@link #adminAuthenticationManagerResolver} routes an incoming token to the right
 * decoder/converter pair by its {@code iss} claim. Both decoders are wrapped in {@link
 * SupplierJwtDecoder} so neither the Customer {@link JwtDecoder} bean lookup nor the
 * blocking network call to fetch Keycloak's JWKS/OIDC discovery document happens at
 * {@code securityFilterChain()} bean-creation time — only lazily, the first time a
 * request actually needs that issuer's decoder. Without this, every application
 * context (including web-layer slice tests that only exercise a public endpoint, and a
 * plain app boot before Keycloak happens to be reachable) would pay that cost eagerly.
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
    private final List<String> allowedOrigins;
    private final List<String> allowedMethods;
    private final List<String> allowedHeaders;

    @Autowired
    public SecurityConfig(ApiAuthenticationEntryPoint authenticationEntryPoint,
                           ApiAccessDeniedHandler accessDeniedHandler,
                           ObjectProvider<JwtDecoder> customerJwtDecoderProvider,
                           @Value("${billing.security.jwt.issuer:subscription-billing}") String customerIssuer,
                           @Value("${billing.security.admin-sso.issuer-uri:"
                                   + "http://localhost:8180/realms/subscription-billing}") String adminIssuerUri,
                           @Value("${billing.security.admin-sso.admin-role:admin}") String adminRole,
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
     * Two issuers, two decoder/converter pairs, routed by the incoming token's (not yet
     * trusted — only used to pick a decoder, which then verifies the signature) {@code
     * iss} claim: this service's own issuer for self-issued CUSTOMER tokens (unchanged
     * from ADR-0003), and Keycloak's realm issuer for ADMIN tokens (ADR-0009).
     * {@code JwtIssuerAuthenticationManagerResolver} only takes an {@link
     * AuthenticationManagerResolver} (no {@code Map} constructor), so the lookup map's
     * {@code get} method reference stands in as one.
     */
    private JwtIssuerAuthenticationManagerResolver adminAuthenticationManagerResolver() {
        JwtDecoder lazyCustomerDecoder = new SupplierJwtDecoder(customerJwtDecoderProvider::getObject);
        AuthenticationManager customerManager = new ProviderManager(
                jwtAuthenticationProvider(lazyCustomerDecoder, customerJwtAuthenticationConverter()));

        JwtDecoder lazyAdminDecoder = new SupplierJwtDecoder(() -> JwtDecoders.fromIssuerLocation(adminIssuerUri));
        AuthenticationManager adminManager = new ProviderManager(
                jwtAuthenticationProvider(lazyAdminDecoder, new KeycloakAdminJwtAuthenticationConverter(adminRole)));

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
     * concept this self-issued-token design has no use for. Keycloak-issued ADMIN
     * tokens use a separate converter ({@link KeycloakAdminJwtAuthenticationConverter})
     * that reads Keycloak's own realm-role claim shape instead.
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
