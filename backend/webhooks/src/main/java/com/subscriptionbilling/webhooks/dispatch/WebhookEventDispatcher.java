package com.subscriptionbilling.webhooks.dispatch;

import com.subscriptionbilling.webhooks.ingestion.GatewayWebhookEvent;

/**
 * Routes a verified, deduped {@link GatewayWebhookEvent} to whatever handles its
 * event type. Kept as its own seam so the modules that own the real per-type business
 * logic (payment success/failure, dispute-opened) can supply their own implementation
 * without this module depending on them.
 */
public interface WebhookEventDispatcher {

    /**
     * @param event the verified, newly-seen event to route
     */
    void dispatch(GatewayWebhookEvent event);
}
