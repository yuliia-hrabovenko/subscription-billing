package com.subscriptionbilling.api.audit;

import com.subscriptionbilling.api.subscription.SignupResponse;
import com.subscriptionbilling.api.support.AbstractPostgresIntegrationTest;
import com.subscriptionbilling.api.support.CountingPaymentGatewayClient;
import com.subscriptionbilling.api.support.PaymentGatewayTestConfig;
import com.subscriptionbilling.audit.ActorType;
import com.subscriptionbilling.audit.AuditLogEntry;
import com.subscriptionbilling.audit.AuditLogEntryRepository;
import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.subscription.Subscription;
import com.subscriptionbilling.billingcore.subscription.SubscriptionRepository;
import com.subscriptionbilling.billingcore.subscription.SubscriptionState;
import com.subscriptionbilling.billingjob.BillingJobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit-logging spec's user story 10 suite: walks every edge in {@code
 * docs/domain/domain-model.md}'s state diagram once and asserts the {@link AuditLogEntry}
 * postcondition (exactly one new row, correct {@code old_state}/{@code new_state}/{@code
 * actor_type}) for each, rather than trusting that coverage stayed correct as an
 * incidental side effect of every other spec's own spot-checks. This is the authoritative
 * coverage for Invariant 12; other specs' tests may still spot-check audit entries where
 * convenient, but a gap here is what should block "audit logging is complete."
 *
 * <p>Each edge is driven through whichever seam its originating spec's own tests already
 * use — the HTTP API seam (customer-initiated signup/cancel/undo-cancel/retry-payment and
 * gateway-initiated dispute webhooks) or the billing-job runner seam (system-initiated
 * suspension and Dunning exhaustion) — per this spec's Testing Decisions, rather than
 * introducing a new seam of its own. Lives in this module because it is the only one with
 * every port implementation (billing-core, invoicing, dunning, payments, webhooks) wired
 * together at once (see {@code BillingJobRunnerIT}'s Javadoc).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditLogEntryStateTransitionCoverageIT extends AbstractPostgresIntegrationTest {

    /** Mirrors {@code WebhookController.SIGNATURE_HEADER_NAME}, package-private to a sibling package. */
    private static final String SIGNATURE_HEADER_NAME = "Stripe-Signature";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private PlanRepository planRepository;

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

    // ---- [*] -> trialing / active (HTTP signup seam) ----

    @Test
    void trialSignupWritesExactlyOneAuditEntryFromNullToTrialingAttributedToCustomer() {
        SignupResponse signup = trialSignUp("trial-signup-" + UUID.randomUUID() + "@example.com");

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(signup.subscriptionId());

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.getOldState()).isNull();
            assertThat(entry.getNewState()).isEqualTo("TRIALING");
            assertThat(entry.getActorType()).isEqualTo(ActorType.CUSTOMER);
        });
    }

    @Test
    void freeSignupWritesExactlyOneAuditEntryFromNullToActiveAttributedToCustomer() {
        SignupResponse signup = signUp("free-signup-" + UUID.randomUUID() + "@example.com");

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(signup.subscriptionId());

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.getOldState()).isNull();
            assertThat(entry.getNewState()).isEqualTo("ACTIVE");
            assertThat(entry.getActorType()).isEqualTo(ActorType.CUSTOMER);
        });
    }

    @Test
    void immediatePaidSignupWritesExactlyOneAuditEntryFromNullToActiveAttributedToCustomer() {
        SignupResponse signup = immediatePaidSignUp("immediate-signup-" + UUID.randomUUID() + "@example.com");

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(signup.subscriptionId());

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.getOldState()).isNull();
            assertThat(entry.getNewState()).isEqualTo("ACTIVE");
            assertThat(entry.getActorType()).isEqualTo(ActorType.CUSTOMER);
        });
    }

    // ---- customer-initiated cancel / undo-cancel (HTTP seam) ----

    @Test
    void cancelFromTrialingWritesExactlyOneAuditEntryFromTrialingToCanceledAttributedToCustomer() {
        SignupResponse signup = trialSignUp("trial-cancel-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat().hasStatusOk();

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(signup.subscriptionId());
        // One for the signup ([*] -> trialing), one for this cancel.
        assertThat(entries).hasSize(2);
        assertThat(entries).filteredOn(entry -> "TRIALING".equals(entry.getOldState()) && "CANCELED".equals(entry.getNewState()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getActorType()).isEqualTo(ActorType.CUSTOMER));
    }

    @Test
    void cancelFromActiveWithABillingCycleWritesExactlyOneAuditEntryFromActiveToPendingCancellationAttributedToCustomer() {
        SignupResponse signup = immediatePaidSignUp("active-cancel-" + UUID.randomUUID() + "@example.com");

        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat().hasStatusOk();

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(signup.subscriptionId());
        // One for the signup ([*] -> active), one for this cancel.
        assertThat(entries).hasSize(2);
        assertThat(entries).filteredOn(entry -> "ACTIVE".equals(entry.getOldState()) && "PENDING_CANCELLATION".equals(entry.getNewState()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getActorType()).isEqualTo(ActorType.CUSTOMER));
    }

    @Test
    void undoCancelFromPendingCancellationWritesExactlyOneAuditEntryFromPendingCancellationToActiveAttributedToCustomer() {
        SignupResponse signup = immediatePaidSignUp("undo-cancel-" + UUID.randomUUID() + "@example.com");
        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat().hasStatusOk();

        mvc.post().uri("/api/v1/subscriptions/{id}/undo-cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat().hasStatusOk();

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(signup.subscriptionId());
        // Signup, cancel, and this undo-cancel.
        assertThat(entries).hasSize(3);
        assertThat(entries).filteredOn(entry -> "PENDING_CANCELLATION".equals(entry.getOldState()) && "ACTIVE".equals(entry.getNewState()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getActorType()).isEqualTo(ActorType.CUSTOMER));
    }

    // ---- system-initiated suspension (billing-job runner seam) ----

    @Test
    void aFailedRenewalChargeWritesExactlyOneAuditEntryFromActiveToSuspendedAttributedToSystem() {
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        LocalDate billingPeriod = LocalDate.of(2026, 4, 10);
        Subscription subscription = seedDueSubscription(
                proPlan, Instant.parse("2026-04-10T00:00:00Z"), billingPeriod, PaymentGatewayTestConfig.DECLINE_TOKEN);

        billingJobRunner.run();

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(subscription.getId());
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.getOldState()).isEqualTo("ACTIVE");
            assertThat(entry.getNewState()).isEqualTo("SUSPENDED");
            assertThat(entry.getActorType()).isEqualTo(ActorType.SYSTEM);
        });
    }

    @Test
    void aFailedTrialConversionChargeWritesExactlyOneAuditEntryFromTrialingToSuspendedAttributedToSystem() {
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        LocalDate trialEndDate = LocalDate.of(2026, 4, 11);
        Subscription subscription = seedDueTrialSubscription(proPlan, trialEndDate, PaymentGatewayTestConfig.DECLINE_TOKEN);

        billingJobRunner.run();

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(subscription.getId());
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.getOldState()).isEqualTo("TRIALING");
            assertThat(entry.getNewState()).isEqualTo("SUSPENDED");
            assertThat(entry.getActorType()).isEqualTo(ActorType.SYSTEM);
        });
    }

    // ---- suspended -> active recovery, scheduled (billing-job seam) and self-service (HTTP seam) ----

    @Test
    void aSuccessfulScheduledDunningRetryWritesExactlyOneAuditEntryFromSuspendedToActiveAttributedToSystem() {
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        LocalDate billingPeriod = LocalDate.of(2026, 4, 15);
        Subscription subscription = seedDueSubscription(
                proPlan, Instant.parse("2026-04-15T00:00:00Z"), billingPeriod, PaymentGatewayTestConfig.DECLINE_TOKEN);

        billingJobRunner.run(); // initial charge fails -> suspended, day-1 retry scheduled
        forceDueToday(subscription.getId());
        updatePaymentMethodToken(subscription.getId(), "tok_visa");

        billingJobRunner.run(); // day-1 scheduled retry succeeds -> restored to active

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(subscription.getId());
        // One for the initial suspension, one for this recovery.
        assertThat(entries).hasSize(2);
        assertThat(entries).filteredOn(entry -> "SUSPENDED".equals(entry.getOldState()) && "ACTIVE".equals(entry.getNewState()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getActorType()).isEqualTo(ActorType.SYSTEM));
    }

    @Test
    void aSuccessfulSelfServiceRetryWritesExactlyOneAuditEntryFromSuspendedToActiveAttributedToCustomer() {
        SignupResponse owner = suspendViaRealDunningFlow("self-retry-" + UUID.randomUUID() + "@example.com");
        updatePaymentMethodToken(owner.subscriptionId(), "tok_visa");

        mvc.post().uri("/api/v1/subscriptions/{id}/retry-payment", owner.subscriptionId())
                .header("Authorization", "Bearer " + owner.accessToken())
                .assertThat().hasStatusOk();

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(owner.subscriptionId());
        // Signup, the suspending decline, and this recovery.
        assertThat(entries).hasSize(3);
        assertThat(entries).filteredOn(entry -> "SUSPENDED".equals(entry.getOldState()) && "ACTIVE".equals(entry.getNewState()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getActorType()).isEqualTo(ActorType.CUSTOMER));
    }

    // ---- suspended -> canceled, Dunning-exhaustion (scheduled, billing-job seam) and customer-triggered (HTTP seam) ----

    @Test
    void dunningExhaustionThroughTheFullScheduledRetryScheduleWritesExactlyOneAuditEntryFromSuspendedToCanceledAttributedToSystem() {
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        LocalDate billingPeriod = LocalDate.of(2026, 4, 20);
        Subscription subscription = seedDueSubscription(
                proPlan, Instant.parse("2026-04-20T00:00:00Z"), billingPeriod, PaymentGatewayTestConfig.DECLINE_TOKEN);

        billingJobRunner.run(); // initial charge fails -> suspended, day-1 retry scheduled
        forceDueToday(subscription.getId());
        billingJobRunner.run(); // day-1 retry fails -> retriesUsed=1, day-3 scheduled
        forceDueToday(subscription.getId());
        billingJobRunner.run(); // day-3 retry fails -> retriesUsed=2, day-7 scheduled
        forceDueToday(subscription.getId());
        billingJobRunner.run(); // day-7 retry fails -> retriesUsed=3, exhausted -> canceled

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(subscription.getId());
        // One for the initial suspension, one for this exhaustion cancellation.
        assertThat(entries).hasSize(2);
        assertThat(entries).filteredOn(entry -> "SUSPENDED".equals(entry.getOldState()) && "CANCELED".equals(entry.getNewState()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getActorType()).isEqualTo(ActorType.SYSTEM));
    }

    @Test
    void dunningExhaustionThroughASelfServiceRetryWritesExactlyOneAuditEntryFromSuspendedToCanceledAttributedToCustomer() {
        SignupResponse owner = suspendViaRealDunningFlow("self-exhaust-" + UUID.randomUUID() + "@example.com");
        forceDueToday(owner.subscriptionId());
        billingJobRunner.run(); // day-1 scheduled retry fails -> retriesUsed=1, day-3 scheduled
        forceDueToday(owner.subscriptionId());
        billingJobRunner.run(); // day-3 scheduled retry fails -> retriesUsed=2, day-7 scheduled

        // The Customer's own self-service attempt, still declined, is the exhausting
        // (3rd) retry -- attribution must follow the trigger, not the retry offset.
        mvc.post().uri("/api/v1/subscriptions/{id}/retry-payment", owner.subscriptionId())
                .header("Authorization", "Bearer " + owner.accessToken())
                .assertThat()
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.state").asString().isEqualTo("CANCELED");

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(owner.subscriptionId());
        // Signup, the initial suspending decline, and this exhausting self-service retry.
        assertThat(entries).hasSize(3);
        assertThat(entries).filteredOn(entry -> "SUSPENDED".equals(entry.getOldState()) && "CANCELED".equals(entry.getNewState()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getActorType()).isEqualTo(ActorType.CUSTOMER));
    }

    // ---- gateway-initiated dispute cancellation (HTTP webhook seam) ----

    @Test
    void aDisputeWebhookWritesExactlyOneAuditEntryFromActiveToCanceledAttributedToGateway() {
        UUID subscriptionId = seedActiveSubscription();
        String eventId = "evt_" + UUID.randomUUID();

        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, CountingPaymentGatewayClient.VALID_SIGNATURE_HEADER)
                .content(disputePayload(eventId, subscriptionId))
                .assertThat().hasStatusOk();

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(subscriptionId);
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.getOldState()).isEqualTo("ACTIVE");
            assertThat(entry.getNewState()).isEqualTo("CANCELED");
            assertThat(entry.getActorType()).isEqualTo(ActorType.GATEWAY);
        });
    }

    @Test
    void aDisputeWebhookWritesExactlyOneAuditEntryFromSuspendedToCanceledAttributedToGateway() {
        SignupResponse owner = suspendViaRealDunningFlow("dispute-suspended-" + UUID.randomUUID() + "@example.com");
        String eventId = "evt_" + UUID.randomUUID();

        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, CountingPaymentGatewayClient.VALID_SIGNATURE_HEADER)
                .content(disputePayload(eventId, owner.subscriptionId()))
                .assertThat().hasStatusOk();

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(owner.subscriptionId());
        // Signup, the suspending decline, and this dispute cancellation.
        assertThat(entries).hasSize(3);
        assertThat(entries).filteredOn(entry -> "SUSPENDED".equals(entry.getOldState()) && "CANCELED".equals(entry.getNewState()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getActorType()).isEqualTo(ActorType.GATEWAY));
    }

    // ---- negative case: a transition absent from the state diagram writes nothing ----

    @Test
    void cancelingAnAlreadyCanceledSubscriptionWritesNoNewAuditLogEntry() {
        SignupResponse signup = trialSignUp("already-canceled-" + UUID.randomUUID() + "@example.com");
        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat().hasStatusOk();
        long entriesAfterFirstCancel = auditLogEntryRepository.findBySubscriptionId(signup.subscriptionId()).size();

        mvc.post().uri("/api/v1/subscriptions/{id}/cancel", signup.subscriptionId())
                .header("Authorization", "Bearer " + signup.accessToken())
                .assertThat()
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("SUBSCRIPTION_ALREADY_CANCELED");

        List<AuditLogEntry> entriesAfterRejectedCancel = auditLogEntryRepository.findBySubscriptionId(signup.subscriptionId());
        assertThat(entriesAfterRejectedCancel).hasSize((int) entriesAfterFirstCancel);
    }

    // ---- helpers ----

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
        MvcTestResult result = mvc.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(immediatePaidSignupBody(proPlanId(), email))
                .exchange();
        return readBody(result, SignupResponse.class);
    }

    /**
     * Drives a Subscription to {@code suspended} through the real Dunning flow
     * (signup, then {@link BillingJobRunner} charging a declined card), mirroring
     * {@code SubscriptionApiIT}'s helper of the same purpose.
     */
    private SignupResponse suspendViaRealDunningFlow(String email) {
        MvcTestResult result = mvc.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(immediatePaidSignupBody(proPlanId(), email, PaymentGatewayTestConfig.DECLINE_TOKEN))
                .exchange();
        SignupResponse signup = readBody(result, SignupResponse.class);

        Subscription subscription = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow();
        subscription.advanceDueDate(LocalDate.now(ZoneOffset.UTC));
        subscriptionRepository.saveAndFlush(subscription);

        billingJobRunner.run();
        return signup;
    }

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

    private Subscription seedDueSubscription(Plan plan, Instant billingCycleAnchor, LocalDate billingPeriod,
                                              String paymentMethodToken) {
        Subscription subscription = Subscription.startPaidImmediately(
                UUID.randomUUID(), seedCustomer(paymentMethodToken), plan, billingCycleAnchor);
        subscription.advanceDueDate(billingPeriod);
        return subscriptionRepository.saveAndFlush(subscription);
    }

    private Subscription seedDueTrialSubscription(Plan plan, LocalDate trialEndDate, String paymentMethodToken) {
        Subscription subscription = Subscription.startTrial(
                UUID.randomUUID(), seedCustomer(paymentMethodToken), plan, Instant.now());
        subscription.advanceDueDate(trialEndDate);
        return subscriptionRepository.saveAndFlush(subscription);
    }

    private UUID seedActiveSubscription() {
        Customer customer = seedCustomer("tok_visa");
        Plan freePlan = planRepository.findByCode("free").orElseThrow();
        Subscription subscription = subscriptionRepository.saveAndFlush(
                new Subscription(UUID.randomUUID(), customer, freePlan, SubscriptionState.ACTIVE));
        return subscription.getId();
    }

    private Customer seedCustomer(String paymentMethodToken) {
        Customer customer = new Customer(UUID.randomUUID(), "audit-coverage-it-" + UUID.randomUUID() + "@example.com");
        customer.setPaymentMethodToken(paymentMethodToken);
        return customerRepository.saveAndFlush(customer);
    }

    private String disputePayload(String eventId, UUID subscriptionId) {
        return """
                {"id":"%s","type":"charge.dispute.created","data":{"object":{"id":"dp_1","metadata":{"subscription_id":"%s"}}}}
                """.formatted(eventId, subscriptionId);
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
                {"planId":"%s","email":"%s","password":"password123!"}
                """.formatted(planId, email);
    }

    private String trialSignupBody(UUID planId, String email) {
        return """
                {"planId":"%s","email":"%s","useTrial":true,"paymentMethodToken":"gw_tok_abc123","password":"password123!"}
                """.formatted(planId, email);
    }

    private String immediatePaidSignupBody(UUID planId, String email) {
        return immediatePaidSignupBody(planId, email, "gw_tok_abc123");
    }

    private String immediatePaidSignupBody(UUID planId, String email, String paymentMethodToken) {
        return """
                {"planId":"%s","email":"%s","useTrial":false,"paymentMethodToken":"%s","password":"password123!"}
                """.formatted(planId, email, paymentMethodToken);
    }
}
