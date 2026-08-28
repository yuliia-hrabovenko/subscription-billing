package com.subscriptionbilling.webhooks.ingestion;

/**
 * A webhook request whose signature does not verify against the payload it was sent
 * with — the request did not genuinely originate from the gateway. Thrown before any
 * dedupe lookup or write, so a forged request can never reach dedupe or dispatch.
 */
public class InvalidWebhookSignatureException extends RuntimeException {

    public InvalidWebhookSignatureException() {
        super("Webhook signature verification failed");
    }
}
