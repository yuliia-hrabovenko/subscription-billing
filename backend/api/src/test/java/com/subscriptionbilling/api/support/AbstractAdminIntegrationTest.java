package com.subscriptionbilling.api.support;

import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Extends {@link AbstractPostgresIntegrationTest} with a mock OIDC provider for
 * {@code AdminApiIT}. The provider is started once and kept alive for the JVM,
 * matching the singleton pattern used by the PostgreSQL and Kafka integration tests.
 *
 * <p>The mock server derives {@code iss} from {@link #ISSUER_ID}. Both token requests
 * and Spring Security discovery/JWKS requests must therefore use the same
 * {@link #issuerUri()}, allowing Testcontainers' dynamic port without fixed-port
 * configuration.
 *
 * <p>{@code JSON_CONFIG} maps the token request's {@code username} to fixture claims,
 * providing admin and non-admin users for authorization tests.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractAdminIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String ISSUER_ID = "subscription-billing-test";
    private static final String TEST_CLIENT_ID = "test-client";

    public static final String ADMIN_USERNAME = "admin-tester";
    public static final String ADMIN_PASSWORD = "admin-test-password!";
    public static final String NON_ADMIN_USERNAME = "viewer-tester";
    public static final String NON_ADMIN_PASSWORD = "viewer-test-password!";

    private static final String JSON_CONFIG = """
            {
              "interactiveLogin": false,
              "tokenCallbacks": [
                {
                  "issuerId": "%s",
                  "tokenExpiry": 120,
                  "requestMappings": [
                    {
                      "requestParam": "username",
                      "match": "%s",
                      "claims": {
                        "sub": "admin-tester-id",
                        "preferred_username": "%s",
                        "groups": ["admin"]
                      }
                    },
                    {
                      "requestParam": "username",
                      "match": "%s",
                      "claims": {
                        "sub": "viewer-tester-id",
                        "preferred_username": "%s",
                        "groups": ["viewer"]
                      }
                    }
                  ]
                }
              ]
            }
            """.formatted(ISSUER_ID, ADMIN_USERNAME, ADMIN_USERNAME, NON_ADMIN_USERNAME, NON_ADMIN_USERNAME);

    protected static final GenericContainer<?> MOCK_OAUTH2_SERVER =
            new GenericContainer<>(DockerImageName.parse("ghcr.io/navikt/mock-oauth2-server:6.0.2"))
                    .withEnv("JSON_CONFIG", JSON_CONFIG)
                    .withExposedPorts(8080)
                    .waitingFor(Wait.forHttp("/" + ISSUER_ID + "/.well-known/openid-configuration")
                            .forPort(8080).forStatusCode(200));

    static {
        MOCK_OAUTH2_SERVER.start();
    }

    protected static String issuerUri() {
        return "http://" + MOCK_OAUTH2_SERVER.getHost() + ":" + MOCK_OAUTH2_SERVER.getMappedPort(8080) + "/" + ISSUER_ID;
    }

    @DynamicPropertySource
    static void adminSsoProperties(DynamicPropertyRegistry registry) {
        registry.add("billing.security.admin-sso.issuer-uri", AbstractAdminIntegrationTest::issuerUri);
    }

    /**
     * A real, mock-server-signed access token in the bare-claim shape (no namespacing —
     * see {@code claim-namespace} in application.yml / docs/operations/auth0-admin-sso-setup.md),
     * for the seeded Admin user.
     */
    protected static String adminToken() {
        return tokenFor(ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    /** Same bare-claim shape as {@link #adminToken()}, for a user with no {@code admin} group. */
    protected static String nonAdminToken() {
        return tokenFor(NON_ADMIN_USERNAME, NON_ADMIN_PASSWORD);
    }

    @SuppressWarnings("unchecked")
    private static String tokenFor(String username, String password) {
        String form = "grant_type=password&client_id=" + TEST_CLIENT_ID
                + "&username=" + encode(username) + "&password=" + encode(password);
        Map<String, Object> response = RestClient.create().post()
                .uri(issuerUri() + "/token")
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
