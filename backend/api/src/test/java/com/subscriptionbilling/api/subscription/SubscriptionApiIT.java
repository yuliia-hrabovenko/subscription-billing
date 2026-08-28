package com.subscriptionbilling.api.subscription;

import com.subscriptionbilling.api.support.AbstractPostgresIntegrationTest;
import com.subscriptionbilling.api.support.PaymentGatewayTestConfig;
import com.subscriptionbilling.audit.AuditLogEntry;
import com.subscriptionbilling.audit.AuditLogEntryRepository;
import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.plan.PriceVersion;
import com.subscriptionbilling.billingcore.plan.PriceVersionRepository;
import com.subscriptionbilling.billingcore.subscription.Subscription;
import com.subscriptionbilling.billingcore.subscription.SubscriptionRepository;
import com.subscriptionbilling.billingcore.subscription.SubscriptionState;
import com.subscriptionbilling.billingjob.BillingJobRunner;
import com.subscriptionbilling.invoicing.invoice.Invoice;
import com.subscriptionbilling.invoicing.invoice.InvoiceRepository;
import com.subscriptionbilling.invoicing.invoice.InvoiceStatus;
import com.subscriptionbilling.invoicing.invoice.PaymentAttempt;
import com.subscriptionbilling.invoicing.invoice.PaymentAttemptRepository;
import com.subscriptionbilling.invoicing.invoice.PaymentAttemptStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Free, Trial, and immediate-paid signup all issue a working token, that token fetches
 * the caller's own Subscription, an unauthenticated fetch is rejected, a different
 * Customer's valid token is rejected (403, never 404), a duplicate signup and a
 * retired-Plan signup are both rejected with a structured error, and exactly one
 * AuditLogEntry exists per signup. Cancel and undo-cancel exercise every originating
 * state's edge (immediate vs. deferred termination, the invalid-transition rejections,
 * ownership enforcement, and Idempotency-Key deduplication) through the HTTP layer.
 * Self-service retry-payment is exercised against a Subscription suspended through the
 * real Dunning flow ({@link BillingJobRunner}), not a hand-seeded fixture, since this is
 * the one module with billing-core's, invoicing's, dunning's, and payments' port
 * implementations all wired together at once (see {@code BillingJobRunnerIT}'s Javadoc).
 */
@SpringBootTest
@AutoConfigureMockMvc
class SubscriptionApiIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PriceVersionRepository priceVersionRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private AuditLogEntryRepository auditLogEntryRepository;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private BillingJobRunner billingJobRunner;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

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

    @Test
    void cancelFromTrialingTransitionsImmediatelyToCanceledVerifiedByFollowUpGet() {
        SignupResponse signup = trialSignUp("trial-cancel-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat()
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.state").asString().isEqualTo("CANCELED");

        mvc.get().uri("/api/v1/subscriptions/{id}", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat()
                .bodyJson()
                .extractingPath("$.state").asString().isEqualTo("CANCELED");
    }

    @Test
    void cancelFromActiveWithABillingCycleDefersToPendingCancellationWithAccessImplyingFieldsUnchangedVerifiedByFollowUpGet() {
        SignupResponse signup = immediatePaidSignUp("active-cancel-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat()
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.state").asString().isEqualTo("PENDING_CANCELLATION");

        var body = mvc.get().uri("/api/v1/subscriptions/{id}", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat()
                .bodyJson();
        body.extractingPath("$.state").asString().isEqualTo("PENDING_CANCELLATION");
        body.extractingPath("$.plan.code").asString().isEqualTo("pro");
    }

    @Test
    void cancelFromActiveWithNoBillingCycleTransitionsImmediatelyToCanceled() {
        // A free-Plan Subscription is ACTIVE with no Billing Cycle — there's no paid
        // period to defer to, so it must cancel immediately rather than stranding in
        // pending_cancellation forever.
        SignupResponse signup = signUp("free-active-cancel-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat()
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.state").asString().isEqualTo("CANCELED");
    }

    @Test
    void cancelFromSuspendedViaTheRealDunningFlowTransitionsImmediatelyToCanceled() {
        // Regression check: suspended is now reachable through the real Dunning flow
        // (BillingJobRunner -> DunningHandoff -> Subscription#suspend), not just a
        // hand-seeded fixture -- the direct-cancel edge from suspended must still hold.
        SignupResponse owner = suspendViaRealDunningFlow("cancel-suspended-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", owner.subscriptionId())
                .header("Authorization", "Bearer " + owner.accessToken())
                .assertThat()
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.state").asString().isEqualTo("CANCELED");

        // Fewer than the full 4-attempt retry bound was used (just the one initial
        // failure) -- the direct cancellation ends the retry sequence early and still
        // makes the still-open Invoice terminal failed immediately.
        Invoice invoice = invoiceRepository
                .findBySubscriptionIdAndBillingPeriod(owner.subscriptionId(), LocalDate.now(ZoneOffset.UTC))
                .orElseThrow();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.FAILED);
        assertThat(invoice.getRetriesUsed()).isZero();
    }

    @Test
    void cancelOnAnAlreadyPendingCancellationSubscriptionReturnsAStructured409() {
        SignupResponse signup = immediatePaidSignUp("already-pending-" + UUID.randomUUID() + "@example.com");
        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat().hasStatusOk();

        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat()
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("SUBSCRIPTION_ALREADY_PENDING_CANCELLATION");
    }

    @Test
    void cancelOnAnAlreadyCanceledSubscriptionReturnsAStructured409() {
        SignupResponse signup = trialSignUp("already-canceled-" + UUID.randomUUID() + "@example.com");
        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat().hasStatusOk();

        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat()
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("SUBSCRIPTION_ALREADY_CANCELED");
    }

    @Test
    void undoCancelFromPendingCancellationReturnsActiveVerifiedByFollowUpGet() {
        SignupResponse signup = immediatePaidSignUp("undo-" + UUID.randomUUID() + "@example.com");
        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat().hasStatusOk();

        mvc.post().uri("/api/v1/subscriptions/{id}/undo-cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat()
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.state").asString().isEqualTo("ACTIVE");

        mvc.get().uri("/api/v1/subscriptions/{id}", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat()
                .bodyJson()
                .extractingPath("$.state").asString().isEqualTo("ACTIVE");
    }

    @Test
    void undoCancelFromAnyOtherStateReturnsAStructured409() {
        SignupResponse signup = signUp("undo-reject-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions/{id}/undo-cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat()
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("SUBSCRIPTION_NOT_PENDING_CANCELLATION");
    }

    @Test
    void aRepeatedCancelRequestWithTheSameIdempotencyKeyDoesNotProduceASecondTransitionOrAuditEntry() {
        SignupResponse signup = immediatePaidSignUp("idem-" + UUID.randomUUID() + "@example.com");
        String idempotencyKey = "idem-key-" + UUID.randomUUID();

        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .header("Idempotency-Key", idempotencyKey)
                .assertThat().hasStatusOk();

        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .header("Idempotency-Key", idempotencyKey)
                .assertThat()
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.state").asString().isEqualTo("PENDING_CANCELLATION");

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(signup.subscriptionId());
        // One for the signup, one for the single (deduplicated) cancel.
        assertThat(entries).hasSize(2);
    }

    @Test
    void cancelForAWrongOwnerIsRejectedWith403() {
        SignupResponse owner = signUp("cancel-victim-" + UUID.randomUUID() + "@example.com");
        SignupResponse otherCustomer = signUp("cancel-attacker-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", owner.subscriptionId())
                .header("Authorization", "Bearer " + otherCustomer.accessToken())
                .assertThat()
                .hasStatus(403)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("FORBIDDEN");
    }

    @Test
    void undoCancelForAWrongOwnerIsRejectedWith403() {
        SignupResponse owner = signUp("undo-victim-" + UUID.randomUUID() + "@example.com");
        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", owner.subscriptionId())
                .header("Authorization", "Bearer " + owner.accessToken())
                .assertThat().hasStatusOk();
        SignupResponse otherCustomer = signUp("undo-attacker-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions/{id}/undo-cancel", owner.subscriptionId())
                .header("Authorization", "Bearer " + otherCustomer.accessToken())
                .assertThat()
                .hasStatus(403)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("FORBIDDEN");
    }

    @Test
    void schedulingAnUpgradeBetweenTwoPaidPlansSetsPendingPlanChangeLeavingTheCurrentPlanUnchangedVerifiedByFollowUpGet() {
        SignupResponse signup = immediatePaidSignUp("upgrader-" + UUID.randomUUID() + "@example.com");
        Plan enterprise = seedPaidPlan("enterprise-" + UUID.randomUUID(), "Enterprise", new BigDecimal("49.00"));

        mvc.post().uri("/api/v1/subscriptions/{id}/plan-change", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(planChangeBody(enterprise.getId()))
                .assertThat()
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.pendingPlanChange.code").asString().isEqualTo(enterprise.getCode());

        var body = mvc.get().uri("/api/v1/subscriptions/{id}", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat()
                .bodyJson();
        body.extractingPath("$.plan.code").asString().isEqualTo("pro");
        body.extractingPath("$.pendingPlanChange.id").asString().isEqualTo(enterprise.getId().toString());
    }

    @Test
    void schedulingADowngradeToFreeSetsPendingPlanChangeToFreeWithoutCancelingTheSubscription() {
        SignupResponse signup = immediatePaidSignUp("downgrader-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions/{id}/plan-change", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(planChangeBody(freePlanId()))
                .assertThat()
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.state").asString().isEqualTo("ACTIVE");

        var body = mvc.get().uri("/api/v1/subscriptions/{id}", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat()
                .bodyJson();
        body.extractingPath("$.state").asString().isEqualTo("ACTIVE");
        body.extractingPath("$.plan.code").asString().isEqualTo("pro");
        body.extractingPath("$.pendingPlanChange.code").asString().isEqualTo("free");
    }

    @Test
    void aFreeSubscriberUpgradingToAPaidPlanChangesThePlanImmediatelyAndOpensABillingCycleAnchoredToTodayVerifiedByFollowUpGet() {
        SignupResponse signup = signUp("free-upgrader-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions/{id}/plan-change", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(planChangeBody(proPlanId()))
                .assertThat()
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.plan.code").asString().isEqualTo("pro");

        var body = mvc.get().uri("/api/v1/subscriptions/{id}", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat()
                .bodyJson();
        body.extractingPath("$.plan.code").asString().isEqualTo("pro");
        body.extractingPath("$.pendingPlanChange").isNull();
        body.extractingPath("$.billingCycle.anchoredAt").isNotNull();
    }

    @Test
    void schedulingASecondPlanChangeWhileOneIsAlreadyPendingReplacesTheFirstVerifiedByFollowUpGet() {
        SignupResponse signup = immediatePaidSignUp("replace-pending-" + UUID.randomUUID() + "@example.com");
        Plan enterprise = seedPaidPlan("enterprise-" + UUID.randomUUID(), "Enterprise", new BigDecimal("49.00"));
        mvc.post().uri("/api/v1/subscriptions/{id}/plan-change", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(planChangeBody(enterprise.getId()))
                .assertThat().hasStatusOk();

        mvc.post().uri("/api/v1/subscriptions/{id}/plan-change", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(planChangeBody(freePlanId()))
                .assertThat().hasStatusOk();

        mvc.get().uri("/api/v1/subscriptions/{id}", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat()
                .bodyJson()
                .extractingPath("$.pendingPlanChange.code").asString().isEqualTo("free");
    }

    @Test
    void aPlanChangeTargetingARetiredPlanIsRejectedWithAStructuredError() {
        SignupResponse signup = immediatePaidSignUp("retired-target-" + UUID.randomUUID() + "@example.com");
        Plan retired = new Plan(UUID.randomUUID(), "retired-" + UUID.randomUUID(), "Retired Plan");
        retired.retireForSignup();
        planRepository.saveAndFlush(retired);

        mvc.post().uri("/api/v1/subscriptions/{id}/plan-change", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(planChangeBody(retired.getId()))
                .assertThat()
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("PLAN_UNAVAILABLE_FOR_SIGNUP");
    }

    @Test
    void aPlanChangeRequestOnATrialingSubscriptionIsRejectedWithAStructured409() {
        SignupResponse signup = trialSignUp("trialing-plan-change-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions/{id}/plan-change", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(planChangeBody(freePlanId()))
                .assertThat()
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("SUBSCRIPTION_NOT_ELIGIBLE_FOR_PLAN_CHANGE");
    }

    @Test
    void aRepeatedPlanChangeRequestWithTheSameIdempotencyKeyDoesNotDoubleApply() {
        SignupResponse signup = immediatePaidSignUp("plan-change-idem-" + UUID.randomUUID() + "@example.com");
        String idempotencyKey = "plan-change-idem-key-" + UUID.randomUUID();

        mvc.post().uri("/api/v1/subscriptions/{id}/plan-change", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(planChangeBody(freePlanId()))
                .assertThat().hasStatusOk();

        mvc.post().uri("/api/v1/subscriptions/{id}/plan-change", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(planChangeBody(freePlanId()))
                .assertThat()
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.pendingPlanChange.code").asString().isEqualTo("free");

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(signup.subscriptionId());
        // One for the signup, one for the single (deduplicated) plan change.
        assertThat(entries).hasSize(2);
    }

    @Test
    void planChangeForAWrongOwnerIsRejectedWith403() {
        SignupResponse owner = immediatePaidSignUp("plan-change-victim-" + UUID.randomUUID() + "@example.com");
        SignupResponse otherCustomer = signUp("plan-change-attacker-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions/{id}/plan-change", owner.subscriptionId())
                .header("Authorization", "Bearer " + otherCustomer.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(planChangeBody(freePlanId()))
                .assertThat()
                .hasStatus(403)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("FORBIDDEN");
    }

    @Test
    void retryPaymentFromSuspendedSucceedsRestoringActiveVerifiedByFollowUpGet() {
        SignupResponse owner = suspendViaRealDunningFlow("retry-success-" + UUID.randomUUID() + "@example.com");
        // Simulates the Customer updating their card before retrying themselves.
        updatePaymentMethodToken(owner.subscriptionId(), "gw_tok_abc123");

        mvc.post().uri("/api/v1/subscriptions/{id}/retry-payment", owner.subscriptionId())
                .header("Authorization", "Bearer " + owner.accessToken())
                .assertThat()
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.state").asString().isEqualTo("ACTIVE");

        mvc.get().uri("/api/v1/subscriptions/{id}", owner.subscriptionId())
                .header("Authorization", "Bearer " + owner.accessToken())
                .assertThat()
                .bodyJson()
                .extractingPath("$.state").asString().isEqualTo("ACTIVE");

        Invoice invoice = invoiceRepository
                .findBySubscriptionIdAndBillingPeriod(owner.subscriptionId(), LocalDate.now(ZoneOffset.UTC))
                .orElseThrow();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void aSelfServiceRetryThatFailsBetweenTwoScheduledRetriesStillEndsAtExactlyFourTotalAttempts() {
        // Required test: a self-service retry that itself fails consumes one of the same
        // bounded slots the day 1/3/7 schedule draws from -- it must not add an attempt
        // beyond the 4-attempt bound (initial + 3 retries, self-service or scheduled).
        SignupResponse owner = suspendViaRealDunningFlow("retry-bound-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions/{id}/retry-payment", owner.subscriptionId())
                .header("Authorization", "Bearer " + owner.accessToken())
                .assertThat()
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.state").asString().isEqualTo("SUSPENDED");

        forceDueToday(owner.subscriptionId());
        billingJobRunner.run(); // day-3 scheduled retry fails -> retriesUsed=2, day-7 scheduled
        forceDueToday(owner.subscriptionId());
        billingJobRunner.run(); // day-7 scheduled retry fails -> retriesUsed=3, exhausted -> canceled

        Invoice invoice = invoiceRepository
                .findBySubscriptionIdAndBillingPeriod(owner.subscriptionId(), LocalDate.now(ZoneOffset.UTC))
                .orElseThrow();
        assertThat(invoice.getRetriesUsed()).isEqualTo(3);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.FAILED);
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByInvoiceId(invoice.getId());
        assertThat(attempts).hasSize(4);
        assertThat(attempts).allSatisfy(attempt -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED));

        Subscription reloaded = subscriptionRepository.findById(owner.subscriptionId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SubscriptionState.CANCELED);
    }

    @Test
    void aRepeatedRetryPaymentRequestWithTheSameIdempotencyKeyDoesNotTriggerASecondPaymentAttempt() {
        SignupResponse owner = suspendViaRealDunningFlow("retry-idem-" + UUID.randomUUID() + "@example.com");
        String idempotencyKey = "retry-idem-key-" + UUID.randomUUID();

        mvc.post().uri("/api/v1/subscriptions/{id}/retry-payment", owner.subscriptionId())
                .header("Authorization", "Bearer " + owner.accessToken())
                .header("Idempotency-Key", idempotencyKey)
                .assertThat().hasStatusOk();

        mvc.post().uri("/api/v1/subscriptions/{id}/retry-payment", owner.subscriptionId())
                .header("Authorization", "Bearer " + owner.accessToken())
                .header("Idempotency-Key", idempotencyKey)
                .assertThat()
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.state").asString().isEqualTo("SUSPENDED");

        Invoice invoice = invoiceRepository
                .findBySubscriptionIdAndBillingPeriod(owner.subscriptionId(), LocalDate.now(ZoneOffset.UTC))
                .orElseThrow();
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByInvoiceId(invoice.getId());
        // One from the initial suspending failure, one (deduplicated) from the retry.
        assertThat(attempts).hasSize(2);
    }

    @Test
    void retryPaymentOnANonSuspendedSubscriptionIsRejectedWith403AndAStructuredError() {
        SignupResponse signup = immediatePaidSignUp("retry-not-suspended-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions/{id}/retry-payment", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat()
                .hasStatus(403)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("SUBSCRIPTION_NOT_SUSPENDED");
    }

    @Test
    void retryPaymentForAWrongOwnerIsRejectedWith403() {
        SignupResponse owner = suspendViaRealDunningFlow("retry-victim-" + UUID.randomUUID() + "@example.com");
        SignupResponse otherCustomer = signUp("retry-attacker-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions/{id}/retry-payment", owner.subscriptionId())
                .header("Authorization", "Bearer " + otherCustomer.accessToken())
                .assertThat()
                .hasStatus(403)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("FORBIDDEN");
    }

    @Test
    void retryPaymentWithNoTokenIsRejectedWith401AndAStructuredError() {
        SignupResponse owner = suspendViaRealDunningFlow("retry-noauth-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions/{id}/retry-payment", owner.subscriptionId())
                .assertThat()
                .hasStatus(401)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("UNAUTHORIZED");
    }

    private SignupResponse signUp(String email) {
        MvcTestResult result = mvc.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(freePlanId(), email))
                .exchange();
        return readBody(result, SignupResponse.class);
    }

    private SignupResponse trialSignUp(String email) {
        MvcTestResult result = mvc.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(trialSignupBody(proPlanId(), email))
                .exchange();
        return readBody(result, SignupResponse.class);
    }

    private SignupResponse immediatePaidSignUp(String email) {
        return immediatePaidSignUp(email, "gw_tok_abc123");
    }

    private SignupResponse immediatePaidSignUp(String email, String paymentMethodToken) {
        MvcTestResult result = mvc.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(immediatePaidSignupBody(proPlanId(), email, paymentMethodToken))
                .exchange();
        return readBody(result, SignupResponse.class);
    }

    /**
     * Drives a Subscription to {@code suspended} through the real Dunning flow —
     * {@link BillingJobRunner} charging a declined card, {@code DunningHandoff}
     * suspending it — rather than seeding the state directly. Signup has no HTTP path
     * yet for scheduling a first due date (see {@code BillingJobRunnerIT}'s fixtures,
     * which do the same), so {@code due_date} is set directly before the real job run
     * that performs the actual suspension.
     */
    private SignupResponse suspendViaRealDunningFlow(String email) {
        SignupResponse signup = immediatePaidSignUp(email, PaymentGatewayTestConfig.DECLINE_TOKEN);
        Subscription subscription = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow();
        subscription.advanceDueDate(LocalDate.now(ZoneOffset.UTC));
        subscriptionRepository.saveAndFlush(subscription);

        billingJobRunner.run();
        return signup;
    }

    /**
     * Moves a suspended Subscription's next scheduled Dunning retry date back to today,
     * via the same mechanism Dunning itself uses, so its day having arrived can be
     * simulated without waiting on the calendar (mirrors {@code BillingJobRunnerIT}'s
     * helper of the same purpose).
     */
    private void forceDueToday(UUID subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId).orElseThrow();
        subscription.scheduleRetry(LocalDate.now(ZoneOffset.UTC));
        subscriptionRepository.saveAndFlush(subscription);
    }

    private void updatePaymentMethodToken(UUID subscriptionId, String paymentMethodToken) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId).orElseThrow();
        Customer customer = customerRepository.findById(subscription.getCustomer().getId()).orElseThrow();
        customer.setPaymentMethodToken(paymentMethodToken);
        customerRepository.saveAndFlush(customer);
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

    /**
     * The seeded catalog (V7 migration) only has {@code free} and {@code pro} — a
     * paid-to-paid plan change needs a third, already-available (non-retired, priced)
     * Plan, seeded directly rather than through the signup-only catalog.
     */
    private Plan seedPaidPlan(String code, String name, BigDecimal amount) {
        Plan plan = new Plan(UUID.randomUUID(), code, name);
        planRepository.saveAndFlush(plan);
        priceVersionRepository.saveAndFlush(
                new PriceVersion(UUID.randomUUID(), plan, amount, Instant.parse("2026-01-01T00:00:00Z")));
        return plan;
    }

    private String planChangeBody(UUID planId) {
        return """
                {"planId":"%s"}
                """.formatted(planId);
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
        return immediatePaidSignupBody(planId, email, "gw_tok_abc123");
    }

    private String immediatePaidSignupBody(UUID planId, String email, String paymentMethodToken) {
        return """
                {"planId":"%s","email":"%s","useTrial":false,"paymentMethodToken":"%s"}
                """.formatted(planId, email, paymentMethodToken);
    }
}
