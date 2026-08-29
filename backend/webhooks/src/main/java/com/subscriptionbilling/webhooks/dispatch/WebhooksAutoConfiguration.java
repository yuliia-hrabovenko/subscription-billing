package com.subscriptionbilling.webhooks.dispatch;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the single {@link WebhookEventDispatcher} bean for any Spring context that
 * does not already define one, composing one dispatcher per event type whose port is
 * available: {@link DisputeWebhookEventDispatcher} once a {@link DisputeCancellationPort}
 * is wired in (billing-core), {@link PaymentOutcomeWebhookEventDispatcher} once a {@link
 * PaymentOutcomeReconciliationPort} is wired in (billing-job's reconciliation reachable
 * through the api module), each falling through to the next, and {@link
 * NoOpWebhookEventDispatcher} at the end of the chain for any event type with no handler
 * wired up. Runs as a Spring Boot auto-configuration, which loads after every explicitly
 * declared bean, so {@link ConditionalOnMissingBean} reliably backs off once a caller
 * registers its own {@link WebhookEventDispatcher} directly.
 */
@AutoConfiguration
public class WebhooksAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(WebhookEventDispatcher.class)
    public WebhookEventDispatcher webhookEventDispatcher(
            ObjectProvider<DisputeCancellationPort> disputeCancellationPort,
            ObjectProvider<PaymentOutcomeReconciliationPort> paymentOutcomeReconciliationPort) {
        WebhookEventDispatcher dispatcher = new NoOpWebhookEventDispatcher();

        DisputeCancellationPort disputePort = disputeCancellationPort.getIfAvailable();
        if (disputePort != null) {
            dispatcher = new DisputeWebhookEventDispatcher(disputePort, dispatcher);
        }

        PaymentOutcomeReconciliationPort paymentPort = paymentOutcomeReconciliationPort.getIfAvailable();
        if (paymentPort != null) {
            dispatcher = new PaymentOutcomeWebhookEventDispatcher(paymentPort, dispatcher);
        }

        return dispatcher;
    }
}
