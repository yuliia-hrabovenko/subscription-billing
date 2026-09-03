package com.subscriptionbilling.payments.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * {@link PaymentGatewayClient} adapter charging a Customer's saved card-on-file
 * PaymentMethod through Stripe's {@code POST /v1/payment_intents} endpoint (test mode,
 * selected by which kind of API key is configured), confirmed immediately and off-session
 * -- {@code paymentMethodToken} is a Stripe PaymentMethod reference (e.g.
 * {@code pm_...}), previously attached to a Customer during signup by {@link
 * com.subscriptionbilling.payments.paymentmethod.PaymentMethodOnboardingService}, not a
 * one-shot Charges-API token; {@code providerCustomerId} (that same Customer's Stripe {@code
 * cus_...} id) must be sent alongside it -- Stripe rejects an attached PaymentMethod's
 * confirmation without it (ADR-0012). Maps Stripe's response shapes onto the three-way {@link
 * ChargeResult}: a {@code card_error} response (which also covers SCA's {@code
 * authentication_required} -- ADR-0011 leaves re-authentication out of scope, so it is
 * handled as an ordinary decline) is a {@link ChargeResult.Declined}; any other error
 * status, a request timeout, or a network-level failure is a {@link
 * ChargeResult.FailedTransiently} so it is never mistaken for a business decline. On
 * success, {@link ChargeResult.Succeeded#gatewayTransactionId()} is the PaymentIntent's
 * own ID, passed through unchanged -- the same ID Stripe's {@code payment_intent.*}
 * webhook events carry as {@code data.object.id}, which is what lets {@code webhooks}
 * module reconciliation correlate against it.
 *
 * <p>PAN safety: sends only the given {@code paymentMethodToken} (a Stripe-issued
 * reference, never a raw card number) and never logs a request or response body — log
 * statements carry only the fields already extracted onto {@link ChargeResult}.
 *
 * <p>{@link #verifyWebhookSignature} implements Stripe's {@code Stripe-Signature} scheme:
 * the header carries a {@code t} (timestamp) and one or more {@code v1} (HMAC-SHA256)
 * values, computed over {@code timestamp + "." + payload} with the webhook signing
 * secret; a payload is authentic only if a {@code v1} value matches the recomputed HMAC
 * <em>and</em> the timestamp falls within {@link #TIMESTAMP_TOLERANCE}, which rejects a
 * replayed request even if it carries a validly-signed, unaltered payload.
 */
public class StripePaymentGatewayClient implements PaymentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentGatewayClient.class);
    private static final String PAYMENT_INTENTS_PATH = "/v1/payment_intents";
    private static final String CARD_ERROR_TYPE = "card_error";
    private static final BigDecimal MINOR_UNITS_PER_MAJOR_UNIT = BigDecimal.valueOf(100);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNED_ELEMENT_SEPARATOR = ".";
    private static final String HEADER_PAIR_SEPARATOR = ",";
    private static final String HEADER_KEY_VALUE_SEPARATOR = "=";
    private static final String TIMESTAMP_KEY = "t";
    private static final String SIGNATURE_KEY = "v1";

    /** Stripe's own default replay-window tolerance for {@code Stripe-Signature}. */
    private static final Duration TIMESTAMP_TOLERANCE = Duration.ofMinutes(5);

    private final StripeProperties properties;
    private final HttpClient httpClient;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StripePaymentGatewayClient(StripeProperties properties) {
        this(properties, Clock.systemUTC());
    }

    StripePaymentGatewayClient(StripeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getRequestTimeout())
                .build();
    }

    /**
     * Charges the given amount, in {@link StripeProperties#getCurrency()}, against the
     * given Stripe PaymentMethod, confirming it immediately as an off-session charge (the
     * Customer is not present to respond to an authentication challenge). A response
     * timing out past {@link StripeProperties#getRequestTimeout()} or any network-level
     * failure resolves to {@link ChargeResult.FailedTransiently} rather than propagating
     * an exception.
     *
     * @param paymentMethodToken the Stripe PaymentMethod reference (e.g. {@code pm_...})
     *                           attached during signup; never a raw PAN
     * @param providerCustomerId the Stripe Customer id (e.g. {@code cus_...}) this
     *                           PaymentMethod is attached to -- Stripe rejects the charge
     *                           without it, since an attached PaymentMethod cannot be
     *                           confirmed against a PaymentIntent that doesn't name its Customer
     * @param amount             the amount to charge, in the billing currency's major unit (e.g. dollars)
     * @return the resolved outcome: success with the PaymentIntent's ID, decline, or transient failure
     */
    @Override
    public ChargeResult charge(String paymentMethodToken, String providerCustomerId, BigDecimal amount) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + PAYMENT_INTENTS_PATH))
                .timeout(properties.getRequestTimeout())
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(chargeRequestBody(paymentMethodToken, providerCustomerId, amount)))
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

    /**
     * Verifies {@code signatureHeader} against {@code payload} per Stripe's {@code
     * Stripe-Signature} scheme, keyed by {@link StripeProperties#getWebhookSigningSecret()}.
     * Rejects a malformed header (missing {@code t} or {@code v1} element), a body or
     * signature that doesn't recompute to a matching HMAC, and a timestamp outside {@link
     * #TIMESTAMP_TOLERANCE} of the current time, so a captured-and-replayed request fails
     * even when its signature was valid when first sent.
     *
     * @param payload         the raw webhook request body, exactly as received, unmodified
     * @param signatureHeader the {@code Stripe-Signature} header value sent alongside the payload
     * @return true only if a signature in the header matches the recomputed HMAC and its timestamp is within tolerance
     */
    @Override
    public boolean verifyWebhookSignature(String payload, String signatureHeader) {
        ParsedSignatureHeader header = parseSignatureHeader(signatureHeader);
        if (header == null) {
            return false;
        }
        if (isOutsideTolerance(header.timestamp())) {
            log.warn("Stripe webhook signature rejected: timestamp outside tolerance");
            return false;
        }

        String expectedSignature = hmacSha256Hex(header.timestamp() + SIGNED_ELEMENT_SEPARATOR + payload);
        boolean matches = header.signatures().stream()
                .anyMatch(candidate -> constantTimeEquals(expectedSignature, candidate));
        if (!matches) {
            log.warn("Stripe webhook signature rejected: no v1 signature matched");
        }
        return matches;
    }

    private ParsedSignatureHeader parseSignatureHeader(String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return null;
        }

        String timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String element : signatureHeader.split(HEADER_PAIR_SEPARATOR)) {
            String[] keyValue = element.split(HEADER_KEY_VALUE_SEPARATOR, 2);
            if (keyValue.length != 2) {
                continue;
            }
            if (TIMESTAMP_KEY.equals(keyValue[0])) {
                timestamp = keyValue[1];
            } else if (SIGNATURE_KEY.equals(keyValue[0])) {
                signatures.add(keyValue[1]);
            }
        }

        if (timestamp == null || signatures.isEmpty()) {
            return null;
        }
        try {
            return new ParsedSignatureHeader(Long.parseLong(timestamp), signatures);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private boolean isOutsideTolerance(long timestampEpochSeconds) {
        Instant timestamp = Instant.ofEpochSecond(timestampEpochSeconds);
        Duration age = Duration.between(timestamp, Instant.now(clock)).abs();
        return age.compareTo(TIMESTAMP_TOLERANCE) > 0;
    }

    private String hmacSha256Hex(String signedPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.getWebhookSigningSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] rawHmac = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(rawHmac);
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException("HmacSHA256 must be available on every supported JVM", impossible);
        }
    }

    private static boolean constantTimeEquals(String expected, String candidate) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }

    private record ParsedSignatureHeader(long timestamp, List<String> signatures) {
    }

    private String chargeRequestBody(String paymentMethodToken, String providerCustomerId, BigDecimal amount) {
        long amountInMinorUnits = amount.multiply(MINOR_UNITS_PER_MAJOR_UNIT)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();
        return "amount=" + amountInMinorUnits
                + "&currency=" + encode(properties.getCurrency())
                + "&payment_method=" + encode(paymentMethodToken)
                + "&customer=" + encode(providerCustomerId)
                + "&confirm=true"
                + "&off_session=true";
    }

    private ChargeResult toChargeResult(HttpResponse<String> response) {
        JsonNode body = readBody(response.body());
        // A confirmed PaymentIntent that succeeds synchronously returns 200 with
        // status=succeeded; a card_error on confirmation surfaces as a non-200 with an
        // "error" body instead, handled below -- there is no PaymentIntent status this
        // adapter needs to branch on beyond that.
        if (response.statusCode() == 200) {
            String gatewayTransactionId = body.path("id").asText();
            log.info("Stripe charge succeeded: gatewayTransactionId={}", gatewayTransactionId);
            return new ChargeResult.Succeeded(gatewayTransactionId);
        }

        String errorType = body.path("error").path("type").asText();
        String errorCode = body.path("error").path("code").asText("");
        String reason = errorCode.isEmpty() ? (errorType.isEmpty() ? "unknown_error" : errorType) : errorCode;

        if (CARD_ERROR_TYPE.equals(errorType)) {
            // Also covers SCA's authentication_required error code: ADR-0011 leaves
            // re-authentication out of scope, so it is handled as an ordinary decline
            // rather than a distinct outcome.
            String gatewayReference = body.path("error").path("payment_intent").path("id").asText(null);
            log.info("Stripe charge declined: reason={} gatewayReference={}", reason, gatewayReference);
            return new ChargeResult.Declined(reason, gatewayReference);
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
