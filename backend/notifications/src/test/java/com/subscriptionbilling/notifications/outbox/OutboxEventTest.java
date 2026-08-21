package com.subscriptionbilling.notifications.outbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxEventTest {

    @Test
    void rejectsANullPublishedAt() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "PAYMENT_SUCCEEDED", "{}");

        assertThatThrownBy(() -> event.markPublished(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBeingMarkedPublishedTwice() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "PAYMENT_SUCCEEDED", "{}");
        event.markPublished(Instant.now());

        assertThatThrownBy(() -> event.markPublished(Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }
}
