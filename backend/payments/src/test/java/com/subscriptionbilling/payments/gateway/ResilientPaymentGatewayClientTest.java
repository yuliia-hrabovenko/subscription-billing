package com.subscriptionbilling.payments.gateway;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.payments.stub.StripeGatewayStub;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigDecimal;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link ResilientPaymentGatewayClient} wrapping a real {@link
 * StripePaymentGatewayClient} against the shared {@link StripeGatewayStub} WireMock
 * mappings: a single timeout is retried and can succeed, repeated timeouts open the
 * circuit breaker, and a decline is never retried.
 */
class ResilientPaymentGatewayClientTest {

    private static final BigDecimal AMOUNT = new BigDecimal("19.99");

    @RegisterExtension
    static WireMockExtension wireMock = StripeGatewayStub.newExtension();

    @Test
    void aSingleTimeoutIsRetriedAndReturnsSuccessWhenTheRetrySucceeds() {
        String token = "tok_timeout_then_success";
        wireMock.stubFor(post(urlPathEqualTo(StripeGatewayStub.CHARGES_PATH))
                .withRequestBody(containing("source=" + token))
                .inScenario("retry-then-success")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("retried")
                .willReturn(aResponse().withFixedDelay((int) StripeGatewayStub.TIMEOUT_DELAY_MILLIS)));
        wireMock.stubFor(post(urlPathEqualTo(StripeGatewayStub.CHARGES_PATH))
                .withRequestBody(containing("source=" + token))
                .inScenario("retry-then-success")
                .whenScenarioStateIs("retried")
                .willReturn(okJson("""
                        {
                          "id": "ch_retrySucceeded001",
                          "object": "charge",
                          "paid": true,
                          "status": "succeeded"
                        }
                        """)));
        ResilientPaymentGatewayClient client = clientWith(testResilienceProperties());

        ChargeResult result = client.charge(token, AMOUNT);

        assertThat(result).isInstanceOf(ChargeResult.Succeeded.class);
        assertThat(((ChargeResult.Succeeded) result).gatewayTransactionId()).isEqualTo("ch_retrySucceeded001");
    }

    @Test
    void repeatedTimeoutsOpenTheCircuitBreaker() {
        GatewayResilienceProperties resilience = testResilienceProperties();
        resilience.getRetry().setMaxAttempts(1);
        resilience.getCircuitBreaker().setSlidingWindowSize(2);
        resilience.getCircuitBreaker().setMinimumNumberOfCalls(2);
        ResilientPaymentGatewayClient client = clientWith(resilience);
        assertThat(client.getCircuitBreakerState()).isEqualTo(CircuitBreaker.State.CLOSED);

        client.charge(StripeGatewayStub.TIMEOUT_TOKEN, AMOUNT);
        client.charge(StripeGatewayStub.TIMEOUT_TOKEN, AMOUNT);

        assertThat(client.getCircuitBreakerState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void aDeclinedChargeIsNeverRetried() {
        ResilientPaymentGatewayClient client = clientWith(testResilienceProperties());

        ChargeResult result = client.charge(StripeGatewayStub.DECLINED_TOKEN, AMOUNT);

        assertThat(result).isInstanceOf(ChargeResult.Declined.class);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(StripeGatewayStub.CHARGES_PATH))
                .withRequestBody(containing("source=" + StripeGatewayStub.DECLINED_TOKEN)));
    }

    @Test
    void aSuccessfulChargePassesThroughWithoutTrippingTheBreaker() {
        ResilientPaymentGatewayClient client = clientWith(testResilienceProperties());

        ChargeResult result = client.charge(StripeGatewayStub.SUCCESS_TOKEN, AMOUNT);

        assertThat(result).isInstanceOf(ChargeResult.Succeeded.class);
        assertThat(client.getCircuitBreakerState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private ResilientPaymentGatewayClient clientWith(GatewayResilienceProperties resilienceProperties) {
        return new ResilientPaymentGatewayClient(new StripePaymentGatewayClient(testStripeProperties()), resilienceProperties);
    }

    private static StripeProperties testStripeProperties() {
        StripeProperties properties = new StripeProperties();
        properties.setBaseUrl(wireMock.getRuntimeInfo().getHttpBaseUrl());
        properties.setApiKey("sk_test_dummy");
        properties.setCurrency("usd");
        properties.setRequestTimeout(Duration.ofMillis(500));
        return properties;
    }

    private static GatewayResilienceProperties testResilienceProperties() {
        GatewayResilienceProperties properties = new GatewayResilienceProperties();
        properties.getRetry().setMaxAttempts(2);
        properties.getRetry().setWaitDuration(Duration.ofMillis(50));
        return properties;
    }
}
