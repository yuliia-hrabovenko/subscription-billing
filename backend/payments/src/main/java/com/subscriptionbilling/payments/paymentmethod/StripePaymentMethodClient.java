package com.subscriptionbilling.payments.paymentmethod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subscriptionbilling.billingjob.paymentmethod.PaymentMethodDetails;
import com.subscriptionbilling.payments.gateway.StripeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Onboarding-side Stripe HTTP calls: creating a Stripe Customer, attaching an
 * already-tokenized PaymentMethod to one, and creating a SetupIntent for the frontend to
 * confirm a card against. The counterpart, for charging, is {@link
 * com.subscriptionbilling.payments.gateway.StripePaymentGatewayClient}; this class
 * follows the same hand-rolled {@link HttpClient} style rather than a full Stripe SDK, to
 * stay consistent with that adapter and avoid a new dependency for a handful of calls.
 *
 * <p>PAN safety: every method here takes or returns only gateway-provided references
 * (customer ids, payment-method ids) or the card metadata Stripe itself reports back
 * (brand/last4/expiry) -- never a raw card number, and no request or response body is
 * ever logged.
 */
public class StripePaymentMethodClient {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentMethodClient.class);
    private static final String CUSTOMERS_PATH = "/v1/customers";
    private static final String SETUP_INTENTS_PATH = "/v1/setup_intents";
    private static final String CARD_TYPE = "card";

    private final StripeProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StripePaymentMethodClient(StripeProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getRequestTimeout())
                .build();
    }

    /**
     * @return a newly created Stripe Customer's id ({@code cus_...})
     * @throws PaymentMethodAttachmentException if the call fails, times out, or Stripe
     *         rejects it
     */
    public String createCustomer() {
        JsonNode body = post(CUSTOMERS_PATH, "");
        String customerId = body.path("id").asText(null);
        if (customerId == null || customerId.isBlank()) {
            throw new PaymentMethodAttachmentException("Stripe customer creation response is missing \"id\"");
        }
        return customerId;
    }

    /**
     * Attaches an already-tokenized PaymentMethod (produced client-side by Stripe.js
     * confirming a SetupIntent) to the given Stripe Customer, and reads back the card's
     * reported brand/last4/expiry from the same response.
     *
     * @param stripeCustomerId        the Stripe Customer to attach to
     * @param providerPaymentMethodId the PaymentMethod reference to attach (e.g. {@code pm_...})
     * @return the gateway-reported shape of the now-attached card
     * @throws PaymentMethodAttachmentException if the call fails, times out, or Stripe
     *         rejects the attach (e.g. an invalid or already-detached PaymentMethod)
     */
    public PaymentMethodDetails attachPaymentMethod(String stripeCustomerId, String providerPaymentMethodId) {
        String path = "/v1/payment_methods/" + encode(providerPaymentMethodId) + "/attach";
        JsonNode body = post(path, "customer=" + encode(stripeCustomerId));
        return toPaymentMethodDetails(body);
    }

    /**
     * @return the {@code client_secret} of a newly created SetupIntent, for the frontend
     *         to confirm a card against via Stripe.js -- created with no {@code customer}
     *         attached yet, since signup collects the card before the Customer record
     *         exists; {@link #attachPaymentMethod} does that association afterward.
     * @throws PaymentMethodAttachmentException if the call fails, times out, or Stripe
     *         rejects it
     */
    public String createSetupIntentClientSecret() {
        JsonNode body = post(SETUP_INTENTS_PATH, "usage=off_session");
        String clientSecret = body.path("client_secret").asText(null);
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new PaymentMethodAttachmentException("Stripe SetupIntent response is missing \"client_secret\"");
        }
        return clientSecret;
    }

    private PaymentMethodDetails toPaymentMethodDetails(JsonNode body) {
        String providerPaymentMethodId = body.path("id").asText(null);
        String type = body.path("type").asText(null);
        if (providerPaymentMethodId == null || providerPaymentMethodId.isBlank() || type == null || type.isBlank()) {
            throw new PaymentMethodAttachmentException("Stripe PaymentMethod response is missing \"id\" or \"type\"");
        }
        if (!CARD_TYPE.equals(type)) {
            return new PaymentMethodDetails(providerPaymentMethodId, type, null, null, null, null);
        }
        JsonNode card = body.path("card");
        String brand = card.path("brand").asText(null);
        String last4 = card.path("last4").asText(null);
        Integer expiryMonth = card.hasNonNull("exp_month") ? card.path("exp_month").asInt() : null;
        Integer expiryYear = card.hasNonNull("exp_year") ? card.path("exp_year").asInt() : null;
        return new PaymentMethodDetails(providerPaymentMethodId, type, brand, last4, expiryMonth, expiryYear);
    }

    private JsonNode post(String path, String formBody) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + path))
                .timeout(properties.getRequestTimeout())
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException timedOut) {
            log.warn("Stripe request to {} timed out after {}", path, properties.getRequestTimeout());
            throw new PaymentMethodAttachmentException("Stripe request to " + path + " timed out", timedOut);
        } catch (IOException failed) {
            log.warn("Stripe request to {} failed: {}", path, failed.getClass().getSimpleName());
            throw new PaymentMethodAttachmentException("Stripe request to " + path + " failed", failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PaymentMethodAttachmentException("Stripe request to " + path + " was interrupted", interrupted);
        }

        JsonNode body = readBody(response.body());
        if (response.statusCode() / 100 != 2) {
            String errorType = body.path("error").path("type").asText("unknown_error");
            String errorMessage = body.path("error").path("message").asText(errorType);
            log.warn("Stripe request to {} failed: status={} type={}", path, response.statusCode(), errorType);
            throw new PaymentMethodAttachmentException(
                    "Stripe request to " + path + " failed with status " + response.statusCode() + ": " + errorMessage);
        }
        return body;
    }

    private JsonNode readBody(String rawBody) {
        try {
            return objectMapper.readTree(rawBody);
        } catch (IOException malformed) {
            return objectMapper.getNodeFactory().objectNode();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
