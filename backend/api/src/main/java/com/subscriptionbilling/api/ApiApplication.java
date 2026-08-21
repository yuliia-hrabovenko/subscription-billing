package com.subscriptionbilling.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

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
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.subscriptionbilling",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
        })
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
