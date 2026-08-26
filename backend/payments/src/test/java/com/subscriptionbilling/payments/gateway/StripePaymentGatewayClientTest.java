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

import java.math.BigDecimal;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link StripePaymentGatewayClient} against the shared {@link
 * StripeGatewayStub} WireMock mappings, standing in for the real Stripe test-mode API.
 */
class StripePaymentGatewayClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = StripeGatewayStub.newExtension();

    private final StripePaymentGatewayClient client = new StripePaymentGatewayClient(testProperties());

    @Test
    void chargeSucceedsReturningStripesChargeIdAsTheGatewayTransactionId() {
        ChargeResult result = client.charge(StripeGatewayStub.SUCCESS_TOKEN, new BigDecimal("19.99"));

        assertThat(result).isInstanceOf(ChargeResult.Succeeded.class);
        assertThat(((ChargeResult.Succeeded) result).gatewayTransactionId()).isEqualTo("ch_3PstripeSuccess001");
    }

    @Test
    void chargeIsDeclinedDistinguishablyFromAnInfraFailureOnTheDeclineStub() {
        ChargeResult result = client.charge(StripeGatewayStub.DECLINED_TOKEN, new BigDecimal("19.99"));

        assertThat(result).isInstanceOf(ChargeResult.Declined.class);
        assertThat(((ChargeResult.Declined) result).reason()).isEqualTo("card_declined");
    }

    @Test
    void chargeFailsTransientlyRatherThanHangingOrDecliningOnTheTimeoutStub() {
        ChargeResult result = client.charge(StripeGatewayStub.TIMEOUT_TOKEN, new BigDecimal("19.99"));

        assertThat(result).isInstanceOf(ChargeResult.FailedTransiently.class);
    }

    @Test
    void verifyWebhookSignatureIsNotYetImplemented() {
        assertThatThrownBy(() -> client.verifyWebhookSignature("{}", "some-signature"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void chargeNeverLogsAPanShapedStringEvenIfTheGatewayResponseContainsOne() {
        String token = "tok_response_contains_a_leaked_pan";
        wireMock.stubFor(post(urlPathEqualTo(StripeGatewayStub.CHARGES_PATH))
                .withRequestBody(containing("source=" + token))
                .willReturn(okJson("""
                        {
                          "id": "ch_leakTest001",
                          "object": "charge",
                          "paid": true,
                          "status": "succeeded",
                          "source": { "number": "4242424242424242", "last4": "4242" }
                        }
                        """)));

        Logger logger = (Logger) LoggerFactory.getLogger(StripePaymentGatewayClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            client.charge(token, new BigDecimal("19.99"));
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
        properties.setCurrency("usd");
        properties.setRequestTimeout(Duration.ofSeconds(2));
        return properties;
    }
}
