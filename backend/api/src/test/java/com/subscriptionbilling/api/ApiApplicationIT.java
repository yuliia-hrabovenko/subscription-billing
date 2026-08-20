package com.subscriptionbilling.api;

import com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Boots the full Spring context (Flyway migrations included) against a Testcontainers
 * Postgres instance — the acceptance-criterion smoke test for this ticket's scaffolding.
 */
class ApiApplicationIT extends AbstractPostgresIntegrationTest {

    @Test
    void contextLoads() {
    }
}
