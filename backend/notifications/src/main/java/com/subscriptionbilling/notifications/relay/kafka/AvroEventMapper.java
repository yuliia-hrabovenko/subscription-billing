package com.subscriptionbilling.notifications.relay.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subscriptionbilling.notifications.outbox.OutboxEvent;
import com.subscriptionbilling.notifications.relay.avro.CancellationConfirmedEvent;
import com.subscriptionbilling.notifications.relay.avro.PaymentFailedEvent;
import com.subscriptionbilling.notifications.relay.avro.PaymentSucceededEvent;
import com.subscriptionbilling.notifications.relay.avro.TrialEndingSoonEvent;
import org.springframework.stereotype.Component;

/**
 * Translates a generic {@link OutboxEvent} (an opaque JSON {@code payload}, per its
 * deliberately schema-less design) into the one Kafka topic and typed Avro record its
 * {@code eventType} designates. This is the single place that knows the JSON shape each
 * of the four trigger points (billing-core, invoicing) writes -- every other component
 * downstream of this class only ever sees the resulting Avro record.
 *
 * <p>Topic-per-event-type, one Avro schema per topic: simpler for a downstream consumer
 * to subscribe to only the notification kinds it cares about than a single topic with a
 * discriminator field, and keeps each schema's evolution independent of the others.
 */
@Component
public class AvroEventMapper {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @throws UnrecognizedEventTypeException if {@code event.getEventType()} has no known
     *                                         schema/topic mapping
     */
    public MappedEvent toAvro(OutboxEvent event) {
        JsonNode payload = readPayload(event);
        String eventId = event.getId().toString();
        return switch (event.getEventType()) {
            case "PAYMENT_SUCCEEDED" -> new MappedEvent("notifications.payment-succeeded",
                    PaymentSucceededEvent.newBuilder()
                            .setEventId(eventId)
                            .setSubscriptionId(text(payload, "subscriptionId"))
                            .setInvoiceId(text(payload, "invoiceId"))
                            .setBillingPeriod(text(payload, "billingPeriod"))
                            .build());
            case "PAYMENT_FAILED" -> new MappedEvent("notifications.payment-failed",
                    PaymentFailedEvent.newBuilder()
                            .setEventId(eventId)
                            .setSubscriptionId(text(payload, "subscriptionId"))
                            .setBillingPeriod(text(payload, "billingPeriod"))
                            .setInvoiceId(text(payload, "invoiceId"))
                            .build());
            case "TRIAL_ENDING_SOON" -> new MappedEvent("notifications.trial-ending-soon",
                    TrialEndingSoonEvent.newBuilder()
                            .setEventId(eventId)
                            .setSubscriptionId(text(payload, "subscriptionId"))
                            .setTrialEndsAt(text(payload, "trialEndsAt"))
                            .build());
            case "CANCELLATION_CONFIRMED" -> new MappedEvent("notifications.cancellation-confirmed",
                    CancellationConfirmedEvent.newBuilder()
                            .setEventId(eventId)
                            .setSubscriptionId(text(payload, "subscriptionId"))
                            .setCustomerId(text(payload, "customerId"))
                            .build());
            default -> throw new UnrecognizedEventTypeException(event.getEventType());
        };
    }

    private JsonNode readPayload(OutboxEvent event) {
        try {
            return objectMapper.readTree(event.getPayload());
        } catch (Exception malformedPayload) {
            throw new UnrecognizedEventTypeException(
                    event.getEventType() + " (malformed payload: " + malformedPayload.getMessage() + ")");
        }
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        return value == null ? null : value.asText();
    }
}
