package com.subscriptionbilling.payments.stub;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hits each of {@link StripeGatewayStub}'s four stub mappings directly over HTTP and
 * asserts the expected Stripe-shaped response comes back, so a break in a mapping file
 * is caught here rather than surfacing first as a failure in a consuming module.
 */
class StripeGatewayStubSmokeTest {

    @RegisterExtension
    static WireMockExtension wireMock = StripeGatewayStub.newExtension();

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void successStubReturnsASucceededPaymentIntent() throws Exception {
        HttpResponse<String> response = postPaymentIntent(StripeGatewayStub.SUCCESS_TOKEN);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo("succeeded");
    }

    @Test
    void declinedStubReturnsStripesCardDeclinedErrorShape() throws Exception {
        HttpResponse<String> response = postPaymentIntent(StripeGatewayStub.DECLINED_TOKEN);

        assertThat(response.statusCode()).isEqualTo(402);
        assertThat(JsonPath.<String>read(response.body(), "$.error.type")).isEqualTo("card_error");
        assertThat(JsonPath.<String>read(response.body(), "$.error.code")).isEqualTo("card_declined");
    }

    @Test
    void timeoutStubDelaysBeforeReturningAPaymentIntent() throws Exception {
        Instant start = Instant.now();

        HttpResponse<String> response = postPaymentIntent(StripeGatewayStub.TIMEOUT_TOKEN);

        assertThat(Duration.between(start, Instant.now()).toMillis())
                .isGreaterThanOrEqualTo(StripeGatewayStub.TIMEOUT_DELAY_MILLIS);
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void disputeWebhookStubReturnsADisputeCreatedEvent() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wireMock.getRuntimeInfo().getHttpBaseUrl() + StripeGatewayStub.DISPUTE_WEBHOOK_EVENT_PATH))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.type")).isEqualTo("charge.dispute.created");
        assertThat(JsonPath.<String>read(response.body(), "$.data.object.reason")).isEqualTo("fraudulent");
    }

    private HttpResponse<String> postPaymentIntent(String paymentMethodToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wireMock.getRuntimeInfo().getHttpBaseUrl() + StripeGatewayStub.PAYMENT_INTENTS_PATH))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "amount=1999&currency=usd&payment_method=" + paymentMethodToken + "&confirm=true&off_session=true"))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
