package com.subscriptionbilling.payments.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * {@link PaymentGatewayClient} adapter charging a card-on-file token through Stripe's
 * {@code POST /v1/charges} endpoint (test mode, selected by which kind of API key is
 * configured). Maps Stripe's response shapes onto the three-way {@link ChargeResult}: a
 * {@code card_error} response is a {@link ChargeResult.Declined}; any other error
 * status, a request timeout, or a network-level failure is a {@link
 * ChargeResult.FailedTransiently} so it is never mistaken for a business decline. On
 * success, {@link ChargeResult.Succeeded#gatewayTransactionId()} is Stripe's own charge
 * ID, passed through unchanged.
 *
 * <p>PAN safety: sends only the given {@code paymentMethodToken} (a Stripe-issued
 * reference, never a raw card number) and never logs a request or response body — log
 * statements carry only the fields already extracted onto {@link ChargeResult}.
 *
 * <p>{@link #verifyWebhookSignature} is not implemented by this adapter; it always
 * throws, since Stripe's real signature scheme is separate, not-yet-built work.
 */
public class StripePaymentGatewayClient implements PaymentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentGatewayClient.class);
    private static final String CHARGES_PATH = "/v1/charges";
    private static final String CARD_ERROR_TYPE = "card_error";
    private static final BigDecimal MINOR_UNITS_PER_MAJOR_UNIT = BigDecimal.valueOf(100);

    private final StripeProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StripePaymentGatewayClient(StripeProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getRequestTimeout())
                .build();
    }

    /**
     * Charges the given amount, in {@link StripeProperties#getCurrency()}, against the
     * given Stripe payment-method token. A response timing out past {@link
     * StripeProperties#getRequestTimeout()} or any network-level failure resolves to
     * {@link ChargeResult.FailedTransiently} rather than propagating an exception.
     *
     * @param paymentMethodToken the Stripe-issued card-on-file token; never a raw PAN
     * @param amount             the amount to charge, in the billing currency's major unit (e.g. dollars)
     * @return the resolved outcome: success with Stripe's charge ID, decline, or transient failure
     */
    @Override
    public ChargeResult charge(String paymentMethodToken, BigDecimal amount) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + CHARGES_PATH))
                .timeout(properties.getRequestTimeout())
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(chargeRequestBody(paymentMethodToken, amount)))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException timedOut) {
            log.warn("Stripe charge request timed out after {}", properties.getRequestTimeout());
            return new ChargeResult.FailedTransiently("gateway_timeout");
        } catch (IOException failed) {
            log.warn("Stripe charge request failed: {}", failed.getClass().getSimpleName());
            return new ChargeResult.FailedTransiently("network_error");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Stripe charge request interrupted");
            return new ChargeResult.FailedTransiently("network_error");
        }

        return toChargeResult(response);
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signatureHeader) {
        throw new UnsupportedOperationException(
                "Stripe webhook signature verification is not implemented by this adapter yet");
    }

    private String chargeRequestBody(String paymentMethodToken, BigDecimal amount) {
        long amountInMinorUnits = amount.multiply(MINOR_UNITS_PER_MAJOR_UNIT)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();
        return "amount=" + amountInMinorUnits
                + "&currency=" + encode(properties.getCurrency())
                + "&source=" + encode(paymentMethodToken);
    }

    private ChargeResult toChargeResult(HttpResponse<String> response) {
        JsonNode body = readBody(response.body());
        if (response.statusCode() == 200) {
            String gatewayTransactionId = body.path("id").asText();
            log.info("Stripe charge succeeded: gatewayTransactionId={}", gatewayTransactionId);
            return new ChargeResult.Succeeded(gatewayTransactionId);
        }

        String errorType = body.path("error").path("type").asText();
        String errorCode = body.path("error").path("code").asText("");
        String reason = errorCode.isEmpty() ? (errorType.isEmpty() ? "unknown_error" : errorType) : errorCode;

        if (CARD_ERROR_TYPE.equals(errorType)) {
            log.info("Stripe charge declined: reason={}", reason);
            return new ChargeResult.Declined(reason);
        }
        log.warn("Stripe charge failed: status={} reason={}", response.statusCode(), reason);
        return new ChargeResult.FailedTransiently(reason);
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
