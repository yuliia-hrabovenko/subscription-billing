# payments — Stripe gateway adapter and shared test double

`StripePaymentGatewayClient` (`src/main/java/.../gateway/StripePaymentGatewayClient.java`)
is the real `PaymentGatewayClient` adapter, charging through Stripe's `POST /v1/charges`
endpoint. `StripeGatewayAutoConfiguration` registers it as the `PaymentGatewayClient` bean
in any Spring context that doesn't already define one (a Spring Boot auto-configuration,
so it always backs off behind an explicitly wired fake). Connection settings are
`StripeProperties` (prefix `billing.payments.stripe`); `apiKey` is overridden via the
`STRIPE_API_KEY` environment variable in real environments — see `api`'s
`application.yml`. Test vs. live mode is determined by which kind of secret key is
configured, not by a different `baseUrl`.

`StripeGatewayStub` (`src/test/java/.../stub/StripeGatewayStub.java`) and its WireMock
mappings (`src/test/resources/wiremock/mappings/*.json`) are packaged into this module's
test-jar and reused by every other module's test suite instead of each one defining its
own gateway mock. Contract tests exercising `PaymentGatewayClient` should run against
this stub as if it were the real Stripe test-mode API.

## Consuming it from another module

Add both dependencies to the consuming module's `pom.xml` (test scope):

```xml
<dependency>
    <groupId>com.subscriptionbilling</groupId>
    <artifactId>payments</artifactId>
    <version>${project.version}</version>
    <type>test-jar</type>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock-standalone</artifactId>
    <scope>test</scope>
</dependency>
```

Then in a test class:

```java
@RegisterExtension
static WireMockExtension wireMock = StripeGatewayStub.newExtension();
```

`wireMock.getRuntimeInfo().getHttpBaseUrl()` gives the base URL to point a real or
test-configured `PaymentGatewayClient` adapter at. See `StripeGatewayStub`'s Javadoc for
the request/response shapes each stub mapping provides.
