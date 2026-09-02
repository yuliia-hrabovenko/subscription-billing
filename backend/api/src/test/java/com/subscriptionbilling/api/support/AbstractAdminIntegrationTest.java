package com.subscriptionbilling.api.support;

import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Extends {@link AbstractPostgresIntegrationTest} with a real Keycloak realm
 * (ADR-0009), for {@code AdminApiIT} only — no other IT class touches {@code
 * /api/v1/admin/**}, so no reason to pay Keycloak's startup cost anywhere else. Same
 * "start once in a static initializer, never torn down until the JVM exits" singleton
 * pattern {@code AbstractPostgresIntegrationTest}/{@code AbstractKafkaIntegrationTest}
 * (notifications module) already use.
 *
 * <p>Keycloak's {@code start-dev} mode derives the {@code iss} claim it stamps on
 * issued tokens from the Host/port the request actually arrived on, rather than a
 * fixed configured hostname — so as long as every caller (this class's token fetch,
 * and {@code SecurityConfig}'s JWKS/discovery fetch via {@code
 * billing.security.admin-sso.issuer-uri}) addresses the container through the exact
 * same {@link #issuerUri()}, the dynamically-mapped Testcontainers port just works with
 * no fixed-port or hostname configuration needed.
 *
 * <p>The imported realm ({@code keycloak/test-realm-export.json}) seeds a public {@code
 * test-client} with the direct-access-grants (Resource Owner Password Credentials)
 * flow enabled — simplest way for a JVM test (no browser) to obtain a real,
 * Keycloak-signed token — plus one user with the {@code admin} realm role and one
 * without, so tests can cover both the happy path and "authenticated but not an Admin".
 * The real SPA never uses this grant type; it uses Authorization Code + PKCE.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractAdminIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String REALM = "subscription-billing-test";
    private static final String TEST_CLIENT_ID = "test-client";

    public static final String ADMIN_USERNAME = "admin-tester";
    public static final String ADMIN_PASSWORD = "admin-test-password!";
    public static final String NON_ADMIN_USERNAME = "viewer-tester";
    public static final String NON_ADMIN_PASSWORD = "viewer-test-password!";

    protected static final GenericContainer<?> KEYCLOAK =
            new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.0"))
                    .withCommand("start-dev", "--import-realm")
                    .withEnv("KEYCLOAK_ADMIN", "admin")
                    .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
                    .withClasspathResourceMapping("keycloak/test-realm-export.json",
                            "/opt/keycloak/data/import/test-realm-export.json", BindMode.READ_ONLY)
                    .withExposedPorts(8080)
                    .waitingFor(Wait.forHttp("/realms/" + REALM).forPort(8080).forStatusCode(200));

    static {
        KEYCLOAK.start();
    }

    protected static String issuerUri() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/" + REALM;
    }

    @DynamicPropertySource
    static void adminSsoProperties(DynamicPropertyRegistry registry) {
        registry.add("billing.security.admin-sso.issuer-uri", AbstractAdminIntegrationTest::issuerUri);
    }

    /** A real Keycloak-signed access token for the seeded Admin user. */
    protected static String adminToken() {
        return tokenFor(ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    /** A real Keycloak-signed access token for a seeded user with no {@code admin} role. */
    protected static String nonAdminToken() {
        return tokenFor(NON_ADMIN_USERNAME, NON_ADMIN_PASSWORD);
    }

    @SuppressWarnings("unchecked")
    private static String tokenFor(String username, String password) {
        String form = "grant_type=password&client_id=" + TEST_CLIENT_ID
                + "&username=" + encode(username) + "&password=" + encode(password);
        Map<String, Object> response = RestClient.create().post()
                .uri(issuerUri() + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        return (String) response.get("access_token");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
