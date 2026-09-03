package com.subscriptionbilling.webhooks.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.subscriptionbilling.webhooks.ingestion.GatewayWebhookEvent;
import com.subscriptionbilling.webhooks.ingestion.WebhookEventType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Proves {@link WebhooksAutoConfiguration}'s composition contract: with neither port
 * available the dispatcher is a no-op; with only one available it's wired directly to
 * that event type's dispatcher; with both available, each event type reaches its own
 * handler rather than one silently shadowing the other.
 */
class WebhooksAutoConfigurationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebhooksAutoConfiguration.class));

    @Test
    void registersANoOpDispatcherWhenNeitherPortIsAvailable() {
        contextRunner.run(context ->
                assertThat(context.getBean(WebhookEventDispatcher.class)).isInstanceOf(NoOpWebhookEventDispatcher.class));
    }

    @Test
    void registersTheDisputeDispatcherWhenOnlyTheDisputeCancellationPortIsAvailable() {
        contextRunner.withUserConfiguration(DisputePortConfig.class).run(context ->
                assertThat(context.getBean(WebhookEventDispatcher.class)).isInstanceOf(DisputeWebhookEventDispatcher.class));
    }

    @Test
    void registersThePaymentOutcomeDispatcherWhenOnlyThatPortIsAvailable() {
        contextRunner.withUserConfiguration(PaymentOutcomePortConfig.class).run(context ->
                assertThat(context.getBean(WebhookEventDispatcher.class)).isInstanceOf(PaymentOutcomeWebhookEventDispatcher.class));
    }

    @Test
    void withBothPortsAvailableADisputeEventReachesTheDisputePortAndAPaymentEventReachesThePaymentPort() {
        contextRunner.withUserConfiguration(DisputePortConfig.class, PaymentOutcomePortConfig.class).run(context -> {
            WebhookEventDispatcher dispatcher = context.getBean(WebhookEventDispatcher.class);
            DisputeCancellationPort disputePort = context.getBean(DisputeCancellationPort.class);
            PaymentOutcomeReconciliationPort paymentPort = context.getBean(PaymentOutcomeReconciliationPort.class);
            UUID subscriptionId = UUID.randomUUID();

            dispatcher.dispatch(disputeEvent(subscriptionId));
            verify(disputePort).cancelForDispute(subscriptionId, "evt_dispute");
            verify(paymentPort, never()).reconcileSucceeded(any(), any(), any());

            dispatcher.dispatch(paymentSucceededEvent(subscriptionId));
            verify(paymentPort).reconcileSucceeded(eq(subscriptionId), eq("ch_1"), any(Instant.class));
        });
    }

    private GatewayWebhookEvent disputeEvent(UUID subscriptionId) {
        return readEvent("evt_dispute", "charge.dispute.created",
                "{\"object\":{\"id\":\"dp_1\",\"metadata\":{\"subscription_id\":\"" + subscriptionId + "\"}}}");
    }

    private GatewayWebhookEvent paymentSucceededEvent(UUID subscriptionId) {
        return readEvent("evt_payment", "payment_intent.succeeded",
                "{\"object\":{\"id\":\"ch_1\",\"metadata\":{\"subscription_id\":\"" + subscriptionId + "\"}}}");
    }

    private GatewayWebhookEvent readEvent(String gatewayEventId, String rawType, String dataJson) {
        try {
            WebhookEventType eventType = WebhookEventType.fromGatewayTypeValue(rawType).orElseThrow();
            return new GatewayWebhookEvent(gatewayEventId, eventType, objectMapper.readTree(dataJson));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Configuration
    static class DisputePortConfig {
        @Bean
        DisputeCancellationPort disputeCancellationPort() {
            return mock(DisputeCancellationPort.class);
        }
    }

    @Configuration
    static class PaymentOutcomePortConfig {
        @Bean
        PaymentOutcomeReconciliationPort paymentOutcomeReconciliationPort() {
            return mock(PaymentOutcomeReconciliationPort.class);
        }
    }
}
