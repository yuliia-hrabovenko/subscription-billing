package com.subscriptionbilling.notifications.relay;

import com.subscriptionbilling.notifications.outbox.OutboxEvent;

/**
 * Publishes one {@link OutboxEvent} to the downstream messaging infrastructure. The
 * transport, schema, and topic/destination resolution are entirely this interface's
 * implementation's concern -- {@link OutboxRelay} only knows publish either completes or
 * throws.
 */
public interface EventPublisher {

    /**
     * Publishes {@code event}, blocking until the broker has acknowledged it or a bounded
     * timeout elapses.
     *
     * @throws EventPublishException if the publish fails or times out; the event's {@code
     *                                published_at} is left null so the next poll retries it
     */
    void publish(OutboxEvent event);
}
