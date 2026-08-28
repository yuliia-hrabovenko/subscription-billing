package com.subscriptionbilling.webhooks.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookPayloadParserTest {

    private final WebhookPayloadParser parser = new WebhookPayloadParser();

    @Test
    void parsesARecognizedEventTypeIntoItsIdAndType() {
        GatewayWebhookEvent event = parser.parse("""
                {"id":"evt_1","type":"charge.dispute.created","data":{"object":{"id":"dp_1"}}}
                """);

        assertThat(event.gatewayEventId()).isEqualTo("evt_1");
        assertThat(event.eventType()).isEqualTo(WebhookEventType.DISPUTE_OPENED);
        assertThat(event.data().path("object").path("id").asText()).isEqualTo("dp_1");
    }

    @Test
    void rejectsPayloadThatIsNotValidJson() {
        assertThatThrownBy(() -> parser.parse("not json"))
                .isInstanceOf(MalformedWebhookPayloadException.class);
    }

    @Test
    void rejectsAJsonArrayBody() {
        assertThatThrownBy(() -> parser.parse("[1,2,3]"))
                .isInstanceOf(MalformedWebhookPayloadException.class);
    }

    @Test
    void rejectsAPayloadMissingTheIdField() {
        assertThatThrownBy(() -> parser.parse("""
                {"type":"charge.dispute.created"}
                """))
                .isInstanceOf(MalformedWebhookPayloadException.class);
    }

    @Test
    void rejectsAPayloadWithABlankIdField() {
        assertThatThrownBy(() -> parser.parse("""
                {"id":"   ","type":"charge.dispute.created"}
                """))
                .isInstanceOf(MalformedWebhookPayloadException.class);
    }

    @Test
    void rejectsAPayloadWithAnUnrecognizedType() {
        assertThatThrownBy(() -> parser.parse("""
                {"id":"evt_1","type":"charge.refunded"}
                """))
                .isInstanceOf(MalformedWebhookPayloadException.class);
    }

    @Test
    void rejectsAPayloadMissingTheTypeField() {
        assertThatThrownBy(() -> parser.parse("""
                {"id":"evt_1"}
                """))
                .isInstanceOf(MalformedWebhookPayloadException.class);
    }
}
