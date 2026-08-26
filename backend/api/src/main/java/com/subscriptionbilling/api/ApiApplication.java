package com.subscriptionbilling.api;

import com.subscriptionbilling.billingjob.BillingJobRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import java.util.Arrays;

/**
 * The runnable Spring Boot application. Base package is {@code com.subscriptionbilling}
 * (not just {@code .api}) so component/entity/repository scanning picks up every other
 * module. The JPA half of that (widening entity/repository scanning past this class's
 * own package) lives in {@link PersistenceConfiguration}, not here directly — see its
 * Javadoc for why that has to be a separate, ordinarily-component-scanned class rather
 * than an annotation on this one.
 *
 * <p>Decomposed from the usual single {@code @SpringBootApplication} into its three
 * constituent annotations because that composed annotation no longer exposes {@code
 * excludeFilters} directly — this is Spring Boot's own documented way to customize a
 * piece {@code @SpringBootApplication} doesn't expose. The exclusion itself: every other
 * module's own {@code *TestApplication} root (billing-core's, audit's, notifications' —
 * the established naming convention for a library module's test-only {@code
 * @SpringBootApplication} root) lands on this module's test classpath too (reused for
 * shared fixtures like {@code AbstractPostgresIntegrationTest}), and scanning this
 * broadly would otherwise pick each one up as a second, competing {@code @Configuration}
 * root — redefining the same beans this class itself already defines and failing
 * context startup.
 *
 * <p>The other two {@code excludeFilters} entries ({@link TypeExcludeFilter}, {@link
 * AutoConfigurationExcludeFilter}) are what {@code @SpringBootApplication} normally
 * supplies by default and what test slices like {@code @WebMvcTest} rely on to exclude
 * non-web beans — decomposing the annotation loses them unless restated explicitly here.
 *
 * <p>This same class also serves as the billing job's entry point: passing {@code
 * --job=billing-run} on the command line (the Kubernetes CronJob's and the manual
 * operator trigger's shared invocation) runs {@link BillingJobRunner} once with no
 * embedded web server, then exits — a distinct startup path, not the web server
 * repurposed to also run the job.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.subscriptionbilling",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
        })
public class ApiApplication {

    private static final Logger log = LoggerFactory.getLogger(ApiApplication.class);
    private static final String BILLING_JOB_ARG = "--job=billing-run";

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * Dispatches to the web server or the one-shot billing job depending on {@code
     * args}, and reports an exit code. {@code additionalSources} is a test-only seam
     * (e.g. to import a fake {@code PaymentGatewayClient}) — production callers pass
     * none, so it has no effect on {@link #main}'s behavior.
     *
     * @param args              the raw command-line arguments
     * @param additionalSources extra Spring configuration classes to boot alongside
     *                          this one; empty in production
     * @return the process exit code: {@code 0} for the web server (which blocks until
     *         shutdown) or a completed job run, non-zero if the job run itself failed
     *         to start or complete
     */
    static int run(String[] args, Class<?>... additionalSources) {
        if (isBillingJobInvocation(args)) {
            return runBillingJob(args, additionalSources);
        }
        SpringApplication.run(ApiApplication.class, args);
        return 0;
    }

    static boolean isBillingJobInvocation(String[] args) {
        return Arrays.asList(args).contains(BILLING_JOB_ARG);
    }

    /**
     * Boots this application with no embedded web server, runs {@link
     * BillingJobRunner#run()} once, and exits. A per-Subscription charge failure does
     * not fail the run — {@link BillingJobRunner} already logs and counts those
     * itself — but a failure before or outside that loop (e.g. the due-subscription
     * query itself failing) does, so Kubernetes reports the Job as failed rather than
     * succeeded.
     */
    private static int runBillingJob(String[] args, Class<?>[] additionalSources) {
        Class<?>[] sources = new Class<?>[additionalSources.length + 1];
        sources[0] = ApiApplication.class;
        System.arraycopy(additionalSources, 0, sources, 1, additionalSources.length);

        ConfigurableApplicationContext context = new SpringApplicationBuilder(sources)
                .web(WebApplicationType.NONE)
                .run(args);
        boolean jobSucceeded;
        try {
            int dueCount = context.getBean(BillingJobRunner.class).run().size();
            log.info("Billing job run complete: {} due subscription(s) selected", dueCount);
            jobSucceeded = true;
        } catch (RuntimeException jobFailed) {
            log.error("Billing job run failed", jobFailed);
            jobSucceeded = false;
        }
        int exitCode = jobSucceeded ? 0 : 1;
        return SpringApplication.exit(context, () -> exitCode);
    }
}
