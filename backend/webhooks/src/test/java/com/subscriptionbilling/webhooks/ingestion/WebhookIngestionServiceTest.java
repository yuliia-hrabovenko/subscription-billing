package com.subscriptionbilling.webhooks.ingestion;

import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import com.subscriptionbilling.webhooks.dedupe.WebhookDedupeService;
import com.subscriptionbilling.webhooks.dispatch.WebhookEventDispatcher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the fixed pipeline order {@link WebhookIngestionService#ingest} enforces:
 * signature verification, then payload parsing, then dedupe, then dispatch — and that
 * each of the four outcome metrics increments only for its own scenario.
 */
@ExtendWith(MockitoExtension.class)
class WebhookIngestionServiceTest {

    private static final String VALID_SIGNATURE = "valid_signature";
    private static final String VALID_PAYLOAD = """
            {"id":"evt_1","type":"charge.dispute.created","data":{}}
            """;

    @Mock
    private PaymentGatewayClient gatewayClient;
    @Mock
    private WebhookDedupeService dedupeService;
    @Mock
    private WebhookEventDispatcher dispatcher;

    private SimpleMeterRegistry meterRegistry;
    private WebhookIngestionService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        WebhookIngestionMetrics metrics = new WebhookIngestionMetrics(meterRegistry);
        service = new WebhookIngestionService(gatewayClient, new WebhookPayloadParser(), dedupeService, dispatcher, metrics);
    }

    @Test
    void anInvalidSignatureIsRejectedBeforeDedupeOrDispatchAreEverTouched() {
        when(gatewayClient.verifyWebhookSignature(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.ingest(VALID_PAYLOAD, "forged"))
                .isInstanceOf(InvalidWebhookSignatureException.class);

        verifyNoInteractions(dedupeService, dispatcher);
        assertThat(counter("webhook_ingestion_rejected_signature_total")).isEqualTo(1.0);
        assertThat(counter("webhook_ingestion_accepted_total")).isZero();
    }

    @Test
    void aVerifiedMalformedPayloadIsRejectedBeforeDedupeOrDispatchAreEverTouched() {
        when(gatewayClient.verifyWebhookSignature(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.ingest("not json", VALID_SIGNATURE))
                .isInstanceOf(MalformedWebhookPayloadException.class);

        verifyNoInteractions(dedupeService, dispatcher);
        assertThat(counter("webhook_ingestion_rejected_malformed_total")).isEqualTo(1.0);
    }

    @Test
    void aNewVerifiedEventIsDedupedThenDispatchedAndCountedAsAccepted() {
        when(gatewayClient.verifyWebhookSignature(anyString(), anyString())).thenReturn(true);
        when(dedupeService.recordIfNew("evt_1")).thenReturn(true);

        service.ingest(VALID_PAYLOAD, VALID_SIGNATURE);

        verify(dispatcher).dispatch(any(GatewayWebhookEvent.class));
        assertThat(counter("webhook_ingestion_accepted_total")).isEqualTo(1.0);
        assertThat(counter("webhook_ingestion_deduped_noop_total")).isZero();
    }

    @Test
    void aRedeliveredEventIsNeverDispatchedAndCountedAsDedupedNoop() {
        when(gatewayClient.verifyWebhookSignature(anyString(), anyString())).thenReturn(true);
        when(dedupeService.recordIfNew("evt_1")).thenReturn(false);

        service.ingest(VALID_PAYLOAD, VALID_SIGNATURE);

        verify(dispatcher, never()).dispatch(any());
        assertThat(counter("webhook_ingestion_deduped_noop_total")).isEqualTo(1.0);
        assertThat(counter("webhook_ingestion_accepted_total")).isZero();
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }
}
