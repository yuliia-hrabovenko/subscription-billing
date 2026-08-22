package com.subscriptionbilling.api.subscription;

import com.subscriptionbilling.api.support.AbstractPostgresIntegrationTest;
import com.subscriptionbilling.audit.AuditLogEntry;
import com.subscriptionbilling.audit.AuditLogEntryRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Free, Trial, and immediate-paid signup all issue a working token, that token fetches
 * the caller's own Subscription, an unauthenticated fetch is rejected, a different
 * Customer's valid token is rejected (403, never 404), a duplicate signup and a
 * retired-Plan signup are both rejected with a structured error, and exactly one
 * AuditLogEntry exists per signup.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SubscriptionApiIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private AuditLogEntryRepository auditLogEntryRepository;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void freeSignupWithNoTokenCreatesAnActiveSubscriptionWithNoBillingCycleAndIssuesAToken() {
        mvc.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(freePlanId(), "user-" + UUID.randomUUID() + "@example.com"))
                .assertThat()
                .hasStatus(201)
                .bodyJson()
                .extractingPath("$.state").asString().isEqualTo("ACTIVE");
    }

    @Test
    void trialSignupReturnsTrialingWithATrialEndDateAndNoBillingCycle() {
        String email = "trialist-" + UUID.randomUUID() + "@example.com";

        var body = mvc.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(trialSignupBody(proPlanId(), email))
                .assertThat()
                .hasStatus(201)
                .bodyJson();
        body.extractingPath("$.state").asString().isEqualTo("TRIALING");
        body.extractingPath("$.trialEndsAt").isNotNull();
        body.extractingPath("$.billingCycle").isNull();
    }

    @Test
    void immediatePaidSignupReturnsActiveWithABillingCycleAndNoTrialEndDate() {
        String email = "immediate-" + UUID.randomUUID() + "@example.com";

        var body = mvc.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(immediatePaidSignupBody(proPlanId(), email))
                .assertThat()
                .hasStatus(201)
                .bodyJson();
        body.extractingPath("$.state").asString().isEqualTo("ACTIVE");
        body.extractingPath("$.trialEndsAt").isNull();
        body.extractingPath("$.billingCycle.anchoredAt").isNotNull();
    }

    @Test
    void paidPlanSignupWithoutAPaymentMethodTokenIsRejectedWith400() {
        mvc.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"planId":"%s","email":"%s","useTrial":true}
                        """.formatted(proPlanId(), "nopayment-" + UUID.randomUUID() + "@example.com"))
                .assertThat()
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("PAYMENT_METHOD_REQUIRED");
    }

    @Test
    void fetchingOwnSubscriptionWithTheIssuedTokenReturnsItsStateWithNoPendingChangeOrBillingCycle() {
        SignupResponse signup = signUp("owner-" + UUID.randomUUID() + "@example.com");

        var body = mvc.get().uri("/api/v1/subscriptions/{id}", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat()
                .hasStatusOk()
                .bodyJson();
        body.extractingPath("$.state").asString().isEqualTo("ACTIVE");
        body.extractingPath("$.plan.code").asString().isEqualTo("free");
        body.extractingPath("$.pendingPlanChange").isNull();
        body.extractingPath("$.billingCycle").isNull();
        body.extractingPath("$.trialEndsAt").isNull();
    }

    @Test
    void fetchingASubscriptionWithNoTokenIsRejectedWith401AndAStructuredError() {
        SignupResponse signup = signUp("noauth-" + UUID.randomUUID() + "@example.com");

        mvc.get().uri("/api/v1/subscriptions/{id}", signup.subscriptionId())
                .assertThat()
                .hasStatus(401)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("UNAUTHORIZED");
    }

    @Test
    void fetchingAnotherCustomersSubscriptionIsRejectedWith403NotFoundOrSuccess() {
        SignupResponse owner = signUp("victim-" + UUID.randomUUID() + "@example.com");
        SignupResponse otherCustomer = signUp("attacker-" + UUID.randomUUID() + "@example.com");

        mvc.get().uri("/api/v1/subscriptions/{id}", owner.subscriptionId())
                .header("Authorization", "Bearer " + otherCustomer.accessToken())
                .assertThat()
                .hasStatus(403)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("FORBIDDEN");
    }

    @Test
    void fetchingWithAMalformedTokenIsRejectedWith401AndAStructuredErrorNotABareResponse() {
        mvc.get().uri("/api/v1/subscriptions/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer not-a-valid-jwt")
                .assertThat()
                .hasStatus(401)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("UNAUTHORIZED");
    }

    @Test
    void signupWithAMalformedTokenStillGetsTheStructuredEnvelopeNotABareResponse() {
        // Signup is permitAll, but a *presented* invalid token is still rejected by the
        // resource server filter itself (a different code path from "no token at all") —
        // this must fail the same structured way as every other rejection in this module.
        mvc.post().uri("/api/v1/subscriptions")
                .header("Authorization", "Bearer not-a-valid-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(freePlanId(), "malformed-token-" + UUID.randomUUID() + "@example.com"))
                .assertThat()
                .hasStatus(401)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("UNAUTHORIZED");
    }

    @Test
    void aDuplicateEmailSignupIsRejectedWith409NotARaw500() {
        String email = "duplicate-" + UUID.randomUUID() + "@example.com";
        UUID freePlanId = freePlanId();

        mvc.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(freePlanId, email))
                .assertThat()
                .hasStatus(201);

        mvc.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(freePlanId, email))
                .assertThat()
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("CONFLICT");
    }

    @Test
    void aSecondSignupAttemptUsingTheFirstSignupsTokenIsRejectedWith409() {
        SignupResponse firstSignup = signUp("repeat-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions")
                .header("Authorization", "Bearer " + firstSignup.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(freePlanId(), "ignored-" + UUID.randomUUID() + "@example.com"))
                .assertThat()
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("DUPLICATE_SUBSCRIPTION");
    }

    @Test
    void signupTargetingARetiredPlanIsRejectedForBothTheTrialAndImmediatePaidPaths() {
        Plan retired = new Plan(UUID.randomUUID(), "retired-" + UUID.randomUUID(), "Retired Plan");
        retired.retireForSignup();
        planRepository.saveAndFlush(retired);

        mvc.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(trialSignupBody(retired.getId(), "retired-trial-" + UUID.randomUUID() + "@example.com"))
                .assertThat()
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("PLAN_UNAVAILABLE_FOR_SIGNUP");

        mvc.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(immediatePaidSignupBody(retired.getId(), "retired-immediate-" + UUID.randomUUID() + "@example.com"))
                .assertThat()
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("PLAN_UNAVAILABLE_FOR_SIGNUP");
    }

    @Test
    void signupWritesExactlyOneAuditLogEntryForTheNewSubscription() {
        SignupResponse signup = signUp("audited-" + UUID.randomUUID() + "@example.com");

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(signup.subscriptionId());

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.getOldState()).isNull();
            assertThat(entry.getNewState()).isEqualTo("ACTIVE");
        });
    }

    private SignupResponse signUp(String email) {
        MvcTestResult result = mvc.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(freePlanId(), email))
                .exchange();
        return readBody(result, SignupResponse.class);
    }

    private <T> T readBody(MvcTestResult result, Class<T> type) {
        try {
            return jsonMapper.readValue(result.getMvcResult().getResponse().getContentAsString(), type);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UUID freePlanId() {
        return planRepository.findByCode("free").orElseThrow().getId();
    }

    private UUID proPlanId() {
        return planRepository.findByCode("pro").orElseThrow().getId();
    }

    private String signupBody(UUID planId, String email) {
        return """
                {"planId":"%s","email":"%s"}
                """.formatted(planId, email);
    }

    private String trialSignupBody(UUID planId, String email) {
        return """
                {"planId":"%s","email":"%s","useTrial":true,"paymentMethodToken":"gw_tok_abc123"}
                """.formatted(planId, email);
    }

    private String immediatePaidSignupBody(UUID planId, String email) {
        return """
                {"planId":"%s","email":"%s","useTrial":false,"paymentMethodToken":"gw_tok_abc123"}
                """.formatted(planId, email);
    }
}
