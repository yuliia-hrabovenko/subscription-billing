package com.subscriptionbilling.webhooks.ingestion;

import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import com.subscriptionbilling.webhooks.dedupe.WebhookDedupeService;
import com.subscriptionbilling.webhooks.dispatch.WebhookEventDispatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The inbound gateway webhook pipeline: signature verification, then payload parsing,
 * then dedupe, then dispatch — in that fixed order. Verification runs before anything
 * else touches the dedupe table or a handler, so a forged request can never reach
 * either; a malformed or unrecognized payload is rejected after verification but
 * before dedupe/dispatch; a redelivered event (its {@code gatewayEventId} already
 * recorded) is a no-op rather than a second dispatch.
 */
@Service
public class WebhookIngestionService {

    private final PaymentGatewayClient gatewayClient;
    private final WebhookPayloadParser payloadParser;
    private final WebhookDedupeService dedupeService;
    private final WebhookEventDispatcher dispatcher;
    private final WebhookIngestionMetrics metrics;

    @Autowired
    public WebhookIngestionService(PaymentGatewayClient gatewayClient, WebhookPayloadParser payloadParser,
                                    WebhookDedupeService dedupeService, WebhookEventDispatcher dispatcher,
                                    WebhookIngestionMetrics metrics) {
        this.gatewayClient = gatewayClient;
        this.payloadParser = payloadParser;
        this.dedupeService = dedupeService;
        this.dispatcher = dispatcher;
        this.metrics = metrics;
    }

    /**
     * Runs one inbound webhook request through the full pipeline.
     *
     * @param rawPayload      the raw request body, exactly as received, unmodified
     * @param signatureHeader the gateway-supplied signature header sent alongside the payload
     * @throws InvalidWebhookSignatureException if the signature does not verify against the payload
     * @throws MalformedWebhookPayloadException if the (verified) payload isn't valid JSON,
     *                                           has no non-blank event id, or has an unrecognized event type
     */
    public void ingest(String rawPayload, String signatureHeader) {
        if (!gatewayClient.verifyWebhookSignature(rawPayload, signatureHeader)) {
            metrics.recordRejectedSignature();
            throw new InvalidWebhookSignatureException();
        }

        GatewayWebhookEvent event;
        try {
            event = payloadParser.parse(rawPayload);
        } catch (MalformedWebhookPayloadException malformed) {
            metrics.recordRejectedMalformed();
            throw malformed;
        }

        if (!dedupeService.recordIfNew(event.gatewayEventId())) {
            metrics.recordDedupedNoop();
            return;
        }

        dispatcher.dispatch(event);
        metrics.recordAccepted();
    }
}
