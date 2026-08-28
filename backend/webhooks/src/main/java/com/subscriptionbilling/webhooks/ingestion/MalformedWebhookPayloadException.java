package com.subscriptionbilling.webhooks.ingestion;

/**
 * A signature-verified webhook request whose body is not valid JSON, is missing its
 * event id, or carries an event {@code type} this system doesn't recognize. Thrown
 * after signature verification but before dedupe/dispatch, so neither a garbled
 * request nor a gateway API change not yet supported here can reach either.
 */
public class MalformedWebhookPayloadException extends RuntimeException {

    public MalformedWebhookPayloadException(String message) {
        super(message);
    }
}
