package com.subscriptionbilling.api;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Widens entity/repository scanning past {@link ApiApplication}'s own package to every
 * module sharing this database (billing-core, audit, notifications) — Spring Boot's
 * default is scoped to the {@code @SpringBootApplication} class's own package regardless
 * of {@code scanBasePackages}, a separate, narrower mechanism from component scanning.
 *
 * <p>Deliberately its own ordinarily-component-scanned {@code @Configuration}, not an
 * annotation on {@link ApiApplication} itself: a slice test like {@code @WebMvcTest}
 * excludes configuration discovered via component scanning, but NOT annotations present
 * directly on the root class it boots — so putting {@link EntityScan}/{@link
 * EnableJpaRepositories} on {@code ApiApplication} would force full JPA startup (and its
 * DataSource requirement) into every web-layer slice test too, defeating the slice.
 */
@Configuration
@EntityScan("com.subscriptionbilling")
@EnableJpaRepositories("com.subscriptionbilling")
public class PersistenceConfiguration {
}
