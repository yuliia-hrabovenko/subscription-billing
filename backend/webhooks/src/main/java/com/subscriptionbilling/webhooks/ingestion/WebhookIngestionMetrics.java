package com.subscriptionbilling.webhooks.ingestion;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The four webhook-ingestion outcome counters: accepted, deduped-noop,
 * rejected-signature, and rejected-malformed.
 */
@Component
public class WebhookIngestionMetrics {

    private final Counter accepted;
    private final Counter dedupedNoop;
    private final Counter rejectedSignature;
    private final Counter rejectedMalformed;

    public WebhookIngestionMetrics(MeterRegistry meterRegistry) {
        this.accepted = Counter.builder("webhook_ingestion_accepted_total")
                .description("Verified, newly-seen webhook events dispatched to a handler")
                .register(meterRegistry);
        this.dedupedNoop = Counter.builder("webhook_ingestion_deduped_noop_total")
                .description("Verified webhook events skipped as a redelivery of an already-processed WebhookEventId")
                .register(meterRegistry);
        this.rejectedSignature = Counter.builder("webhook_ingestion_rejected_signature_total")
                .description("Webhook requests rejected for failing gateway signature verification")
                .register(meterRegistry);
        this.rejectedMalformed = Counter.builder("webhook_ingestion_rejected_malformed_total")
                .description("Signature-verified webhook requests rejected for a malformed or unrecognized payload")
                .register(meterRegistry);
    }

    void recordAccepted() {
        accepted.increment();
    }

    void recordDedupedNoop() {
        dedupedNoop.increment();
    }

    void recordRejectedSignature() {
        rejectedSignature.increment();
    }

    void recordRejectedMalformed() {
        rejectedMalformed.increment();
    }
}
