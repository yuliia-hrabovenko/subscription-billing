package com.subscriptionbilling.payments.gateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.payments.stub.StripeGatewayStub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link StripePaymentGatewayClient} against the shared {@link
 * StripeGatewayStub} WireMock mappings, standing in for the real Stripe test-mode API.
 */
class StripePaymentGatewayClientTest {

    private static final String WEBHOOK_SIGNING_SECRET = "whsec_test_secret";
    private static final Instant FIXED_NOW = Instant.parse("2024-01-01T00:00:00Z");
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @RegisterExtension
    static WireMockExtension wireMock = StripeGatewayStub.newExtension();

    private final StripePaymentGatewayClient client =
            new StripePaymentGatewayClient(testProperties(), Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

    @Test
    void chargeSucceedsReturningStripesChargeIdAsTheGatewayTransactionId() {
        ChargeResult result = client.charge(StripeGatewayStub.SUCCESS_TOKEN, StripeGatewayStub.CUSTOMER_ID, new BigDecimal("19.99"));

        assertThat(result).isInstanceOf(ChargeResult.Succeeded.class);
        assertThat(((ChargeResult.Succeeded) result).gatewayTransactionId()).isEqualTo("ch_3PstripeSuccess001");
    }

    @Test
    void chargeSendsTheProviderCustomerIdStripeRequiresForAnAlreadyAttachedPaymentMethod() {
        client.charge(StripeGatewayStub.SUCCESS_TOKEN, StripeGatewayStub.CUSTOMER_ID, new BigDecimal("19.99"));

        wireMock.verify(postRequestedFor(urlPathEqualTo(StripeGatewayStub.PAYMENT_INTENTS_PATH))
                .withRequestBody(containing("customer=" + StripeGatewayStub.CUSTOMER_ID)));
    }

    @Test
    void chargeIsDeclinedDistinguishablyFromAnInfraFailureOnTheDeclineStub() {
        ChargeResult result = client.charge(StripeGatewayStub.DECLINED_TOKEN, StripeGatewayStub.CUSTOMER_ID, new BigDecimal("19.99"));

        assertThat(result).isInstanceOf(ChargeResult.Declined.class);
        assertThat(((ChargeResult.Declined) result).reason()).isEqualTo("card_declined");
    }

    @Test
    void chargeFailsTransientlyRatherThanHangingOrDecliningOnTheTimeoutStub() {
        ChargeResult result = client.charge(StripeGatewayStub.TIMEOUT_TOKEN, StripeGatewayStub.CUSTOMER_ID, new BigDecimal("19.99"));

        assertThat(result).isInstanceOf(ChargeResult.FailedTransiently.class);
    }

    @Test
    void validlySignedDisputeWebhookPayloadVerifiesAsAuthentic() throws Exception {
        String payload = fetchDisputeWebhookPayload();
        String signatureHeader = signatureHeader(FIXED_NOW.getEpochSecond(), payload, WEBHOOK_SIGNING_SECRET);

        assertThat(client.verifyWebhookSignature(payload, signatureHeader)).isTrue();
    }

    @Test
    void tamperedPayloadBodyIsRejectedEvenWithAnOtherwiseValidHeader() throws Exception {
        String payload = fetchDisputeWebhookPayload();
        String signatureHeader = signatureHeader(FIXED_NOW.getEpochSecond(), payload, WEBHOOK_SIGNING_SECRET);

        assertThat(client.verifyWebhookSignature(payload + "tampered", signatureHeader)).isFalse();
    }

    @Test
    void payloadSignedWithTheWrongSecretIsRejected() throws Exception {
        String payload = fetchDisputeWebhookPayload();
        String signatureHeader = signatureHeader(FIXED_NOW.getEpochSecond(), payload, "whsec_some_other_secret");

        assertThat(client.verifyWebhookSignature(payload, signatureHeader)).isFalse();
    }

    @Test
    void payloadSignedOutsideTheReplayWindowToleranceIsRejected() throws Exception {
        String payload = fetchDisputeWebhookPayload();
        long expiredTimestamp = FIXED_NOW.minus(Duration.ofMinutes(10)).getEpochSecond();
        String signatureHeader = signatureHeader(expiredTimestamp, payload, WEBHOOK_SIGNING_SECRET);

        assertThat(client.verifyWebhookSignature(payload, signatureHeader)).isFalse();
    }

    @Test
    void malformedSignatureHeaderMissingAV1ElementIsRejected() throws Exception {
        String payload = fetchDisputeWebhookPayload();

        assertThat(client.verifyWebhookSignature(payload, "t=" + FIXED_NOW.getEpochSecond())).isFalse();
    }

    @Test
    void chargeNeverLogsAPanShapedStringEvenIfTheGatewayResponseContainsOne() {
        String token = "pm_response_contains_a_leaked_pan";
        wireMock.stubFor(post(urlPathEqualTo(StripeGatewayStub.PAYMENT_INTENTS_PATH))
                .withRequestBody(containing("payment_method=" + token))
                .willReturn(okJson("""
                        {
                          "id": "ch_leakTest001",
                          "object": "payment_intent",
                          "status": "succeeded",
                          "payment_method": { "number": "4242424242424242", "last4": "4242" }
                        }
                        """)));

        Logger logger = (Logger) LoggerFactory.getLogger(StripePaymentGatewayClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            client.charge(token, StripeGatewayStub.CUSTOMER_ID, new BigDecimal("19.99"));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.matches(".*\\d{12,19}.*"));
    }

    private static StripeProperties testProperties() {
        StripeProperties properties = new StripeProperties();
        properties.setBaseUrl(wireMock.getRuntimeInfo().getHttpBaseUrl());
        properties.setApiKey("sk_test_dummy");
        properties.setWebhookSigningSecret(WEBHOOK_SIGNING_SECRET);
        properties.setCurrency("usd");
        properties.setRequestTimeout(Duration.ofSeconds(2));
        return properties;
    }

    private static String fetchDisputeWebhookPayload() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wireMock.getRuntimeInfo().getHttpBaseUrl() + StripeGatewayStub.DISPUTE_WEBHOOK_EVENT_PATH))
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String signatureHeader(long timestampEpochSeconds, String payload, String secret) throws Exception {
        String signedPayload = timestampEpochSeconds + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestampEpochSeconds + ",v1=" + signature;
    }
}
