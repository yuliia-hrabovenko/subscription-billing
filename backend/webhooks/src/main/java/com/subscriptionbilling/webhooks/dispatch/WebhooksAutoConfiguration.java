package com.subscriptionbilling.webhooks.dispatch;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@link WebhookEventDispatcher} bean for any Spring context that does not
 * already define one: {@link DisputeWebhookEventDispatcher} once a {@link
 * DisputeCancellationPort} is available (i.e. billing-core is wired in), otherwise {@link
 * NoOpWebhookEventDispatcher}. Runs as a Spring Boot auto-configuration, which loads after
 * every explicitly declared bean, so {@link ConditionalOnMissingBean} reliably backs off
 * once a real per-event-type dispatcher is registered elsewhere.
 */
@AutoConfiguration
public class WebhooksAutoConfiguration {

    @Bean
    @ConditionalOnBean(DisputeCancellationPort.class)
    @ConditionalOnMissingBean(WebhookEventDispatcher.class)
    public WebhookEventDispatcher disputeWebhookEventDispatcher(DisputeCancellationPort disputeCancellationPort) {
        return new DisputeWebhookEventDispatcher(disputeCancellationPort);
    }

    @Bean
    @ConditionalOnMissingBean(WebhookEventDispatcher.class)
    public WebhookEventDispatcher webhookEventDispatcher() {
        return new NoOpWebhookEventDispatcher();
    }
}
