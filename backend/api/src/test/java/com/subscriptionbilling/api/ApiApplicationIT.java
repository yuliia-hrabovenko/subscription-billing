package com.subscriptionbilling.api;

import com.subscriptionbilling.api.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Boots the full Spring context (Flyway migrations included) against a Testcontainers
 * Postgres instance — the acceptance-criterion smoke test for this ticket's scaffolding.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiApplicationIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void contextLoads() {
    }

    @Test
    void prometheusScrapeEndpointIsReachableWithNoBearerToken() {
        // docker-compose's Prometheus service (docker/prometheus/prometheus.yml) has no
        // Authorization header to present -- this endpoint must stay outside
        // SecurityConfig's JWT-required catch-all.
        mvc.get().uri("/actuator/prometheus").assertThat().hasStatusOk();
    }
}
