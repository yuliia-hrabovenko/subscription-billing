package com.subscriptionbilling.billingcore;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only {@code @SpringBootApplication} root so billing-core's own tests (a
 * library module with no runnable app of its own) can boot a full Spring context.
 * Not packaged into the main jar — only into the test-jar other modules depend on
 * to reuse {@link com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest}.
 */
@SpringBootApplication
public class BillingCoreTestApplication {
}
