package com.subscriptionbilling.billingcore;

import com.subscriptionbilling.billingjob.BillingJobRunner;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
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
 *
 * <p>Decomposed from the composed {@code @SpringBootApplication} (see {@code
 * ApiApplication}'s Javadoc for why that annotation doesn't expose {@code
 * excludeFilters} directly here) specifically to exclude {@link BillingJobRunner}:
 * billing-core's own module dependencies (audit, dunning) satisfy every port
 * BillingJobRunner needed through ticket #10, but success-path charging added a port
 * only the invoicing module implements — a dependency billing-core intentionally
 * doesn't have. BillingJobRunner's full orchestration is exercised in the api module's
 * integration tests instead, where every port has a real implementation on the
 * classpath; this module's own tests still exercise its adapters
 * ({@link com.subscriptionbilling.billingcore.subscription.DueSubscriptionsAdapter} and
 * friends) directly.
 *
 * <p>The other two {@code excludeFilters} entries ({@link TypeExcludeFilter}, {@link
 * AutoConfigurationExcludeFilter}) are what {@code @SpringBootApplication} normally
 * supplies by default and what test slices like {@code @DataJpaTest} rely on to exclude
 * non-repository beans — decomposing the annotation loses them unless restated here too
 * (see {@code ApiApplication}'s Javadoc for the same point).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.subscriptionbilling",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = BillingJobRunner.class)
        })
@EntityScan("com.subscriptionbilling")
@EnableJpaRepositories("com.subscriptionbilling")
public class BillingCoreTestApplication {
}
