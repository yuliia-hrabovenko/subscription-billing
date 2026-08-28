package com.subscriptionbilling.api.webhook;

import com.subscriptionbilling.api.support.AbstractPostgresIntegrationTest;
import com.subscriptionbilling.api.support.CountingPaymentGatewayClient;
import com.subscriptionbilling.webhooks.dedupe.WebhookEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@code POST /api/v1/webhooks/gateway} end to end against a real Postgres
 * instance: an invalid signature never reaches the dedupe table, a validly-signed
 * malformed/unrecognized payload is rejected only after signature verification, a
 * redelivered event is a provable no-op, the endpoint needs no bearer token, and all
 * four outcome metrics increment for their respective scenario.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebhookIngestionIT extends AbstractPostgresIntegrationTest {

    private static final String SIGNATURE_HEADER_NAME = WebhookController.SIGNATURE_HEADER_NAME;
    private static final String VALID_SIGNATURE = CountingPaymentGatewayClient.VALID_SIGNATURE_HEADER;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void anInvalidSignatureRequestIsRejectedBeforeAnyDedupeTableWriteWithAStructuredError() {
        String eventId = "evt_" + UUID.randomUUID();
        long rowsBefore = webhookEventRepository.count();

        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, "forged-signature")
                .content(disputePayload(eventId))
                .assertThat()
                .hasStatus(401)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("INVALID_WEBHOOK_SIGNATURE");

        assertThat(webhookEventRepository.count()).isEqualTo(rowsBefore);
    }

    @Test
    void aValidlySignedMalformedPayloadIsRejectedWithAStructuredErrorAfterSignatureVerification() {
        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, VALID_SIGNATURE)
                .content("not json")
                .assertThat()
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("MALFORMED_WEBHOOK_PAYLOAD");
    }

    @Test
    void aValidlySignedUnrecognizedEventTypeIsRejectedAsMalformed() {
        String eventId = "evt_" + UUID.randomUUID();

        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, VALID_SIGNATURE)
                .content("""
                        {"id":"%s","type":"charge.refunded"}
                        """.formatted(eventId))
                .assertThat()
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("MALFORMED_WEBHOOK_PAYLOAD");
    }

    @Test
    void aRedeliveredEventWithTheSameWebhookEventIdIsANoOp() {
        String eventId = "evt_" + UUID.randomUUID();
        String payload = disputePayload(eventId);

        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, VALID_SIGNATURE)
                .content(payload)
                .assertThat().hasStatusOk();

        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, VALID_SIGNATURE)
                .content(payload)
                .assertThat().hasStatusOk();

        assertThat(webhookEventRepository.findAll().stream()
                .filter(row -> row.getGatewayEventId().equals(eventId))
                .count()).isEqualTo(1);
    }

    @Test
    void theEndpointIsReachableWithNoBearerToken() {
        String eventId = "evt_" + UUID.randomUUID();

        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, VALID_SIGNATURE)
                .content(disputePayload(eventId))
                .assertThat()
                .hasStatusOk();
    }

    @Test
    void allFourOutcomeMetricsIncrementForTheirRespectiveScenario() {
        double acceptedBefore = counter("webhook_ingestion_accepted_total");
        double dedupedBefore = counter("webhook_ingestion_deduped_noop_total");
        double rejectedSignatureBefore = counter("webhook_ingestion_rejected_signature_total");
        double rejectedMalformedBefore = counter("webhook_ingestion_rejected_malformed_total");
        String eventId = "evt_" + UUID.randomUUID();
        String payload = disputePayload(eventId);

        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, "forged-signature")
                .content(payload)
                .assertThat().hasStatus(401);

        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, VALID_SIGNATURE)
                .content("not json")
                .assertThat().hasStatus(400);

        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, VALID_SIGNATURE)
                .content(payload)
                .assertThat().hasStatusOk();

        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, VALID_SIGNATURE)
                .content(payload)
                .assertThat().hasStatusOk();

        assertThat(counter("webhook_ingestion_accepted_total")).isEqualTo(acceptedBefore + 1);
        assertThat(counter("webhook_ingestion_deduped_noop_total")).isEqualTo(dedupedBefore + 1);
        assertThat(counter("webhook_ingestion_rejected_signature_total")).isEqualTo(rejectedSignatureBefore + 1);
        assertThat(counter("webhook_ingestion_rejected_malformed_total")).isEqualTo(rejectedMalformedBefore + 1);
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }

    private String disputePayload(String eventId) {
        return """
                {"id":"%s","type":"charge.dispute.created","data":{"object":{"id":"dp_1"}}}
                """.formatted(eventId);
    }
}
