package com.subscriptionbilling.billingcore;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Test-only {@code @SpringBootApplication} root so billing-core's own tests (a
 * library module with no runnable app of its own) can boot a full Spring context.
 * Not packaged into the main jar — only into the test-jar other modules depend on
 * to reuse {@link com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest}.
 *
 * <p>Mirrors {@code ApiApplication}'s exact convention (scan + explicit entity/repository
 * widening — see its Javadoc for why {@code scanBasePackages} alone isn't enough):
 * billing-core depends on {@code audit}'s {@code AuditLogEntryRepository} at runtime
 * now, not merely at compile time, so a full-context test here needs that bean
 * discoverable too.
 */
@SpringBootApplication(scanBasePackages = "com.subscriptionbilling")
@EntityScan("com.subscriptionbilling")
@EnableJpaRepositories("com.subscriptionbilling")
public class BillingCoreTestApplication {
}
