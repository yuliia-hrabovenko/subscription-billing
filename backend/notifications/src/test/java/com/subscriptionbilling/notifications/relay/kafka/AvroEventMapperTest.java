package com.subscriptionbilling.notifications.relay.kafka;

import com.subscriptionbilling.notifications.outbox.OutboxEvent;
import com.subscriptionbilling.notifications.relay.avro.CancellationConfirmedEvent;
import com.subscriptionbilling.notifications.relay.avro.PaymentFailedEvent;
import com.subscriptionbilling.notifications.relay.avro.PaymentSucceededEvent;
import com.subscriptionbilling.notifications.relay.avro.TrialEndingSoonEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvroEventMapperTest {

    private final AvroEventMapper mapper = new AvroEventMapper();

    @Test
    void mapsAPaymentSucceededOutboxEventToItsTopicAndAvroRecord() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(eventId, "PAYMENT_SUCCEEDED",
                "{\"invoiceId\":\"inv-1\",\"subscriptionId\":\"sub-1\",\"billingPeriod\":\"2026-08\"}");

        MappedEvent mapped = mapper.toAvro(event);

        assertThat(mapped.topic()).isEqualTo("notifications.payment-succeeded");
        PaymentSucceededEvent value = (PaymentSucceededEvent) mapped.value();
        assertThat(value.getEventId()).isEqualTo(eventId.toString());
        assertThat(value.getInvoiceId()).isEqualTo("inv-1");
        assertThat(value.getSubscriptionId()).isEqualTo("sub-1");
        assertThat(value.getBillingPeriod()).isEqualTo("2026-08");
    }

    @Test
    void mapsAPaymentFailedOutboxEventToItsTopicAndAvroRecord() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(eventId, "PAYMENT_FAILED",
                "{\"subscriptionId\":\"sub-1\",\"billingPeriod\":\"2026-08\",\"invoiceId\":\"corr-1\"}");

        MappedEvent mapped = mapper.toAvro(event);

        assertThat(mapped.topic()).isEqualTo("notifications.payment-failed");
        PaymentFailedEvent value = (PaymentFailedEvent) mapped.value();
        assertThat(value.getSubscriptionId()).isEqualTo("sub-1");
        assertThat(value.getBillingPeriod()).isEqualTo("2026-08");
        assertThat(value.getInvoiceId()).isEqualTo("corr-1");
    }

    @Test
    void mapsATrialEndingSoonOutboxEventToItsTopicAndAvroRecord() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(eventId, "TRIAL_ENDING_SOON",
                "{\"subscriptionId\":\"sub-1\",\"trialEndsAt\":\"2026-09-02T00:00:00Z\"}");

        MappedEvent mapped = mapper.toAvro(event);

        assertThat(mapped.topic()).isEqualTo("notifications.trial-ending-soon");
        TrialEndingSoonEvent value = (TrialEndingSoonEvent) mapped.value();
        assertThat(value.getSubscriptionId()).isEqualTo("sub-1");
        assertThat(value.getTrialEndsAt()).isEqualTo("2026-09-02T00:00:00Z");
    }

    @Test
    void mapsACancellationConfirmedOutboxEventToItsTopicAndAvroRecord() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(eventId, "CANCELLATION_CONFIRMED",
                "{\"subscriptionId\":\"sub-1\",\"customerId\":\"cust-1\"}");

        MappedEvent mapped = mapper.toAvro(event);

        assertThat(mapped.topic()).isEqualTo("notifications.cancellation-confirmed");
        CancellationConfirmedEvent value = (CancellationConfirmedEvent) mapped.value();
        assertThat(value.getSubscriptionId()).isEqualTo("sub-1");
        assertThat(value.getCustomerId()).isEqualTo("cust-1");
    }

    @Test
    void rejectsAnUnrecognizedEventType() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "SOME_FUTURE_EVENT", "{}");

        assertThatThrownBy(() -> mapper.toAvro(event)).isInstanceOf(UnrecognizedEventTypeException.class);
    }

    @Test
    void rejectsAMalformedPayload() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "PAYMENT_SUCCEEDED", "{not valid json");

        assertThatThrownBy(() -> mapper.toAvro(event)).isInstanceOf(UnrecognizedEventTypeException.class);
    }
}
