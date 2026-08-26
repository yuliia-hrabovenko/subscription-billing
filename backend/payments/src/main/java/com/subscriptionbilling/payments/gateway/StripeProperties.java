package com.subscriptionbilling.payments.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Connection settings for the real Stripe adapter. Carries working defaults (a dev-only
 * placeholder API key, Stripe's API host, USD, a request timeout well under the shared
 * WireMock stub's timeout-scenario delay) so no configuration is required to boot;
 * {@code api}'s application.yml overrides {@code apiKey} via the {@code STRIPE_API_KEY}
 * environment variable in real environments, the same convention already used for this
 * project's DB credentials and JWT secret. Test vs. live mode is determined entirely by
 * which kind of secret key is configured here, not by a different {@code baseUrl}.
 */
@ConfigurationProperties(prefix = "billing.payments.stripe")
public class StripeProperties {

    private String apiKey = "sk_test_dev_only_override_with_STRIPE_API_KEY_env_var_in_every_real_environment";
    private String baseUrl = "https://api.stripe.com";
    private String currency = "usd";
    private Duration requestTimeout = Duration.ofSeconds(3);

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }
}
