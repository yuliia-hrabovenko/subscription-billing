package com.subscriptionbilling.notifications.relay;

/**
 * An {@link EventPublisher#publish} attempt failed or timed out. Always treated as
 * transient by {@link OutboxRelay} -- the triggering {@code OutboxEvent} is left
 * unpublished and retried on the next poll, never dead-lettered by this module.
 */
public class EventPublishException extends RuntimeException {

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
