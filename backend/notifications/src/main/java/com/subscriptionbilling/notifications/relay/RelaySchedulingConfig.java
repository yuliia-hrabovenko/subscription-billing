package com.subscriptionbilling.notifications.relay;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@link OutboxRelay#poll()}'s {@code @Scheduled} trigger. Its own {@code
 * @Configuration} rather than an annotation directly on a Spring Boot application root
 * (of which this library module has none of its own) -- whichever runnable app pulls
 * this module in via component scanning gets the relay's background poller for free.
 */
@Configuration
@EnableScheduling
public class RelaySchedulingConfig {
}
