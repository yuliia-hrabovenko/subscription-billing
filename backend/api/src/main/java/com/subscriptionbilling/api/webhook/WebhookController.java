package com.subscriptionbilling.api.webhook;

import com.subscriptionbilling.webhooks.ingestion.WebhookIngestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The inbound gateway webhook receiver. Authenticated entirely by gateway signature
 * verification (delegated to {@link WebhookIngestionService}) rather than a customer
 * bearer token, since the gateway has no customer identity to present.
 */
@RestController
public class WebhookController {

    static final String SIGNATURE_HEADER_NAME = "Stripe-Signature";

    private final WebhookIngestionService ingestionService;

    @Autowired
    public WebhookController(WebhookIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/api/v1/webhooks/gateway")
    public ResponseEntity<Void> receiveGatewayEvent(@RequestBody String rawPayload,
                                                      @RequestHeader(SIGNATURE_HEADER_NAME) String signatureHeader) {
        ingestionService.ingest(rawPayload, signatureHeader);
        return ResponseEntity.ok().build();
    }
}
