package com.subscriptionbilling.webhooks.dispatch;

import com.subscriptionbilling.webhooks.ingestion.GatewayWebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Placeholder {@link WebhookEventDispatcher} for an event type with no handler wired
 * up yet: logs and does nothing. The default bean until a real dispatcher for that
 * type's business logic is registered.
 */
public class NoOpWebhookEventDispatcher implements WebhookEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NoOpWebhookEventDispatcher.class);

    @Override
    public void dispatch(GatewayWebhookEvent event) {
        log.info("No handler wired up for webhook event type {} (gatewayEventId={}); no-op",
                event.eventType(), event.gatewayEventId());
    }
}
