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

    /**
     * Only the one-shot billing job reports an exit code Kubernetes should act on, so
     * only that path calls {@code System.exit}. {@code SpringApplication.run} for the
     * web server returns as soon as the context is refreshed — it does not block — so
     * the process staying up afterward depends entirely on Tomcat's non-daemon threads;
     * forcing {@code System.exit} unconditionally here (as this used to do) tore that
     * down and killed the web server moments after every startup.
     */
    public static void main(String[] args) {
        if (isBillingJobInvocation(args)) {
            System.exit(run(args));
        } else {
            run(args);
        }
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
     * @return the process exit code: {@code 0} for the web server (whose caller must not
     *         treat this as a signal to exit — see {@link #main}) or a completed job
     *         run, non-zero if the job run itself failed to start or complete
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
