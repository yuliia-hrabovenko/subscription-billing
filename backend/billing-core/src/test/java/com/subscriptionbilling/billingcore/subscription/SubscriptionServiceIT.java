package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.audit.AuditLogEntry;
import com.subscriptionbilling.audit.AuditLogEntryRepository;
import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.idempotency.IdempotencyKeyRecord;
import com.subscriptionbilling.billingcore.idempotency.IdempotencyKeyRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class SubscriptionServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private AuditLogEntryRepository auditLogEntryRepository;

    @Test
    void freeSignupPersistsACustomerAnActiveSubscriptionAndExactlyOneAuditLogEntry() {
        UUID freePlanId = planRepository.findByCode("free").orElseThrow().getId();
        String email = "signup-" + UUID.randomUUID() + "@example.com";

        SubscriptionSignupResult result = subscriptionService.signUp(
                new SignupCommand(freePlanId, email, null, false, null, "corr-it-1"));

        assertThat(result.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(result.accessToken()).isNotBlank();

        Subscription persisted = subscriptionRepository.findById(result.subscriptionId()).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(SubscriptionState.ACTIVE);
        // Fetched via CustomerRepository directly, not persisted.getCustomer(): that
        // relation is lazy, and this assertion runs outside the service's own
        // transaction, same as a real caller inspecting persisted state after the fact.
        Customer persistedCustomer = customerRepository.findById(persisted.getCustomer().getId()).orElseThrow();
        assertThat(persistedCustomer.getEmail()).isEqualTo(email);

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(result.subscriptionId());
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.getOldState()).isNull();
            assertThat(entry.getNewState()).isEqualTo("ACTIVE");
            assertThat(entry.getCorrelationId()).isEqualTo("corr-it-1");
        });
    }

    @Test
    void trialSignupPersistsATrialingSubscriptionWithATrialEndDateAndNoBillingCycle() {
        UUID proPlanId = planRepository.findByCode("pro").orElseThrow().getId();
        String email = "trialist-" + UUID.randomUUID() + "@example.com";

        SubscriptionSignupResult result = subscriptionService.signUp(
                new SignupCommand(proPlanId, email, null, true, "gw_tok_abc123", "corr-it-2"));

        assertThat(result.state()).isEqualTo(SubscriptionState.TRIALING);
        assertThat(result.trialEndsAt()).isAfter(Instant.now());
        assertThat(result.billingCycleAnchor()).isNull();

        Subscription persisted = subscriptionRepository.findById(result.subscriptionId()).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(SubscriptionState.TRIALING);
        assertThat(persisted.isTrialUsed()).isTrue();
        assertThat(persisted.getTrialEndsAt()).isNotNull();
        assertThat(persisted.getBillingCycleAnchor()).isNull();

        Customer persistedCustomer = customerRepository.findById(persisted.getCustomer().getId()).orElseThrow();
        assertThat(persistedCustomer.getPaymentMethodToken()).isEqualTo("gw_tok_abc123");
    }

    @Test
    void immediatePaidSignupPersistsAnActiveSubscriptionWithABillingCycleAndNoTrialEndDate() {
        UUID proPlanId = planRepository.findByCode("pro").orElseThrow().getId();
        String email = "immediate-" + UUID.randomUUID() + "@example.com";

        SubscriptionSignupResult result = subscriptionService.signUp(
                new SignupCommand(proPlanId, email, null, false, "gw_tok_abc123", "corr-it-3"));

        assertThat(result.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(result.trialEndsAt()).isNull();
        assertThat(result.billingCycleAnchor()).isNotNull();

        Subscription persisted = subscriptionRepository.findById(result.subscriptionId()).orElseThrow();
        assertThat(persisted.isTrialUsed()).isFalse();
        assertThat(persisted.getTrialEndsAt()).isNull();
        assertThat(persisted.getBillingCycleAnchor()).isNotNull();
    }

    @Test
    void aSecondSignupAttemptForACustomerWithAnExistingNonCanceledSubscriptionIsRejected() {
        UUID freePlanId = planRepository.findByCode("free").orElseThrow().getId();
        SubscriptionSignupResult firstSignup = subscriptionService.signUp(
                new SignupCommand(freePlanId, "repeat-" + UUID.randomUUID() + "@example.com", null, false, null, "corr-it-4"));
        UUID customerId = subscriptionRepository.findById(firstSignup.subscriptionId()).orElseThrow().getCustomer().getId();

        assertThatThrownBy(() -> subscriptionService.signUp(
                new SignupCommand(freePlanId, null, customerId, false, null, "corr-it-5")))
                .isInstanceOf(DuplicateSubscriptionException.class);
    }

    @Test
    void aCustomerWithOnlyACanceledSubscriptionCanSignUpAgainAndGetsABrandNewSubscriptionWithFreshTrialEligibility() {
        UUID freePlanId = planRepository.findByCode("free").orElseThrow().getId();
        Plan freePlan = planRepository.findById(freePlanId).orElseThrow();
        Customer customer = customerRepository.saveAndFlush(
                new Customer(UUID.randomUUID(), "resubscriber-" + UUID.randomUUID() + "@example.com"));
        Subscription canceledSubscription = new Subscription(UUID.randomUUID(), customer, freePlan, SubscriptionState.CANCELED);
        subscriptionRepository.saveAndFlush(canceledSubscription);

        SubscriptionSignupResult result = subscriptionService.signUp(
                new SignupCommand(freePlanId, null, customer.getId(), false, null, "corr-it-6"));

        assertThat(result.subscriptionId()).isNotEqualTo(canceledSubscription.getId());
        Subscription newSubscription = subscriptionRepository.findById(result.subscriptionId()).orElseThrow();
        assertThat(newSubscription.getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(newSubscription.isTrialUsed()).isFalse();

        // The old row is untouched — re-subscribing creates a new row, never reactivates one.
        Subscription stillCanceled = subscriptionRepository.findById(canceledSubscription.getId()).orElseThrow();
        assertThat(stillCanceled.getState()).isEqualTo(SubscriptionState.CANCELED);
    }

    @Test
    void getOwnSubscriptionDeniesAnotherCustomersValidTokenAccessToItsSubscription() {
        UUID freePlanId = planRepository.findByCode("free").orElseThrow().getId();
        SubscriptionSignupResult ownerSignup = subscriptionService.signUp(
                new SignupCommand(freePlanId, "owner-" + UUID.randomUUID() + "@example.com", null, false, null, "corr-it-7"));
        SubscriptionSignupResult otherSignup = subscriptionService.signUp(
                new SignupCommand(freePlanId, "other-" + UUID.randomUUID() + "@example.com", null, false, null, "corr-it-8"));

        Subscription otherSubscription = subscriptionRepository.findById(otherSignup.subscriptionId()).orElseThrow();
        UUID ownerCustomerId = subscriptionRepository.findById(ownerSignup.subscriptionId())
                .orElseThrow().getCustomer().getId();

        assertThat(otherSubscription.getCustomer().getId()).isNotEqualTo(ownerCustomerId);
        assertThatThrownBy(() -> subscriptionService.getOwnSubscription(otherSignup.subscriptionId(), ownerCustomerId))
                .isInstanceOf(SubscriptionAccessDeniedException.class);
    }

    @Test
    void cancelFromTrialingPersistsCanceledImmediately() {
        UUID proPlanId = planRepository.findByCode("pro").orElseThrow().getId();
        SubscriptionSignupResult signup = subscriptionService.signUp(new SignupCommand(
                proPlanId, "trialist-" + UUID.randomUUID() + "@example.com", null, true, "gw_tok_abc123", "corr-it-9"));
        UUID customerId = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow().getCustomer().getId();

        subscriptionService.cancel(signup.subscriptionId(), customerId, null, "corr-it-10");

        Subscription persisted = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(SubscriptionState.CANCELED);
    }

    @Test
    void cancelFromActiveWithABillingCyclePersistsPendingCancellationWithPlanUnchanged() {
        UUID proPlanId = planRepository.findByCode("pro").orElseThrow().getId();
        SubscriptionSignupResult signup = subscriptionService.signUp(new SignupCommand(
                proPlanId, "active-" + UUID.randomUUID() + "@example.com", null, false, "gw_tok_abc123", "corr-it-11"));
        UUID customerId = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow().getCustomer().getId();

        subscriptionService.cancel(signup.subscriptionId(), customerId, null, "corr-it-12");

        Subscription persisted = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(SubscriptionState.PENDING_CANCELLATION);
        assertThat(persisted.getPlan().getId()).isEqualTo(proPlanId);
    }

    @Test
    void cancelFromActiveWithNoBillingCyclePersistsCanceledImmediately() {
        // A free-Plan Subscription is ACTIVE with no Billing Cycle — there's no paid
        // period to defer to, so it must cancel immediately rather than stranding in
        // pending_cancellation forever (nothing ever advances a free Subscription out
        // of it).
        UUID freePlanId = planRepository.findByCode("free").orElseThrow().getId();
        SubscriptionSignupResult signup = subscriptionService.signUp(new SignupCommand(
                freePlanId, "free-active-" + UUID.randomUUID() + "@example.com", null, false, null, "corr-it-11b"));
        UUID customerId = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow().getCustomer().getId();

        subscriptionService.cancel(signup.subscriptionId(), customerId, null, "corr-it-12b");

        Subscription persisted = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(SubscriptionState.CANCELED);
    }

    @Test
    void cancelFromSuspendedTestSeededFixturePersistsCanceledImmediately() {
        UUID proPlanId = planRepository.findByCode("pro").orElseThrow().getId();
        Plan proPlan = planRepository.findById(proPlanId).orElseThrow();
        Customer customer = customerRepository.saveAndFlush(
                new Customer(UUID.randomUUID(), "suspended-" + UUID.randomUUID() + "@example.com"));
        Subscription suspended = new Subscription(UUID.randomUUID(), customer, proPlan, SubscriptionState.SUSPENDED);
        subscriptionRepository.saveAndFlush(suspended);

        subscriptionService.cancel(suspended.getId(), customer.getId(), null, "corr-it-13");

        Subscription persisted = subscriptionRepository.findById(suspended.getId()).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(SubscriptionState.CANCELED);
    }

    @Test
    void cancelOnAnAlreadyPendingCancellationSubscriptionIsRejected() {
        UUID proPlanId = planRepository.findByCode("pro").orElseThrow().getId();
        SubscriptionSignupResult signup = subscriptionService.signUp(new SignupCommand(
                proPlanId, "double-cancel-" + UUID.randomUUID() + "@example.com", null, false, "gw_tok_abc123", "corr-it-14"));
        UUID customerId = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow().getCustomer().getId();
        subscriptionService.cancel(signup.subscriptionId(), customerId, null, "corr-it-15");

        assertThatThrownBy(() -> subscriptionService.cancel(signup.subscriptionId(), customerId, null, "corr-it-16"))
                .isInstanceOf(SubscriptionAlreadyPendingCancellationException.class);
    }

    @Test
    void cancelOnAnAlreadyCanceledSubscriptionIsRejected() {
        UUID freePlanId = planRepository.findByCode("free").orElseThrow().getId();
        SubscriptionSignupResult signup = subscriptionService.signUp(new SignupCommand(
                freePlanId, "double-cancel-free-" + UUID.randomUUID() + "@example.com", null, false, null, "corr-it-14b"));
        UUID customerId = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow().getCustomer().getId();
        subscriptionService.cancel(signup.subscriptionId(), customerId, null, "corr-it-15b");

        assertThatThrownBy(() -> subscriptionService.cancel(signup.subscriptionId(), customerId, null, "corr-it-16b"))
                .isInstanceOf(SubscriptionAlreadyCanceledException.class);
    }

    @Test
    void undoCancelFromPendingCancellationPersistsActive() {
        UUID proPlanId = planRepository.findByCode("pro").orElseThrow().getId();
        SubscriptionSignupResult signup = subscriptionService.signUp(new SignupCommand(
                proPlanId, "undo-" + UUID.randomUUID() + "@example.com", null, false, "gw_tok_abc123", "corr-it-17"));
        UUID customerId = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow().getCustomer().getId();
        subscriptionService.cancel(signup.subscriptionId(), customerId, null, "corr-it-18");

        subscriptionService.undoCancel(signup.subscriptionId(), customerId, null, "corr-it-19");

        Subscription persisted = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(SubscriptionState.ACTIVE);
    }

    @Test
    void undoCancelFromAnyOtherStateIsRejected() {
        UUID freePlanId = planRepository.findByCode("free").orElseThrow().getId();
        SubscriptionSignupResult signup = subscriptionService.signUp(new SignupCommand(
                freePlanId, "undo-reject-" + UUID.randomUUID() + "@example.com", null, false, null, "corr-it-20"));
        UUID customerId = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow().getCustomer().getId();

        assertThatThrownBy(() -> subscriptionService.undoCancel(signup.subscriptionId(), customerId, null, "corr-it-21"))
                .isInstanceOf(SubscriptionNotPendingCancellationException.class);
    }

    @Test
    void aRepeatedCancelWithTheSameIdempotencyKeyPersistsExactlyOneAuditLogEntryAndDoesNotDoubleApply() {
        UUID proPlanId = planRepository.findByCode("pro").orElseThrow().getId();
        SubscriptionSignupResult signup = subscriptionService.signUp(new SignupCommand(
                proPlanId, "idem-cancel-" + UUID.randomUUID() + "@example.com", null, false, "gw_tok_abc123", "corr-it-22"));
        UUID customerId = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow().getCustomer().getId();
        String idempotencyKey = "idem-key-" + UUID.randomUUID();

        subscriptionService.cancel(signup.subscriptionId(), customerId, idempotencyKey, "corr-it-23");
        SubscriptionView retried = subscriptionService.cancel(signup.subscriptionId(), customerId, idempotencyKey, "corr-it-24");

        assertThat(retried.state()).isEqualTo(SubscriptionState.PENDING_CANCELLATION);
        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(signup.subscriptionId());
        // One entry for the signup, one for the (single, deduplicated) cancel.
        assertThat(entries).hasSize(2);
        assertThat(entries).filteredOn(entry -> "PENDING_CANCELLATION".equals(entry.getNewState())).hasSize(1);
    }

    @Test
    void concurrentCancelRequestsWithTheSameIdempotencyKeyProduceNoExceptionAndExactlyOneTransition() throws Exception {
        // Regression test: IdempotencyService.recordIfNew runs inside cancel()'s own
        // @Transactional method. Before it was given its own REQUIRES_NEW transaction,
        // a lost race there (two concurrent inserts of the same idempotency key) would
        // abort cancel()'s entire ambient transaction in Postgres — even though the
        // DataIntegrityViolationException was caught — surfacing as an unhandled
        // exception from the "losing" caller instead of a graceful deduplicated result.
        UUID freePlanId = planRepository.findByCode("free").orElseThrow().getId();
        SubscriptionSignupResult signup = subscriptionService.signUp(new SignupCommand(
                freePlanId, "concurrent-cancel-" + UUID.randomUUID() + "@example.com", null, false, null, "corr-it-25"));
        UUID customerId = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow().getCustomer().getId();
        String idempotencyKey = "concurrent-key-" + UUID.randomUUID();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Callable<SubscriptionView> attempt = () -> {
                ready.countDown();
                go.await();
                return subscriptionService.cancel(signup.subscriptionId(), customerId, idempotencyKey, "corr-it-26");
            };

            List<Future<SubscriptionView>> futures = List.of(executor.submit(attempt), executor.submit(attempt));
            ready.await();
            go.countDown();

            for (Future<SubscriptionView> future : futures) {
                // Must not throw: neither the winner nor the deduplicated "loser" should
                // ever see a poisoned-transaction failure. The "loser"'s returned state
                // isn't asserted here: it reflects that request's own pre-dedup read,
                // which — thanks to Hibernate's first-level cache — can legitimately
                // still show the pre-transition state if it raced the winner's commit;
                // what actually matters (checked below) is that the transition itself
                // was never re-run and the Subscription ends up correctly CANCELED.
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
        }

        assertThat(subscriptionRepository.findById(signup.subscriptionId()).orElseThrow().getState())
                .isEqualTo(SubscriptionState.CANCELED);
        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(signup.subscriptionId());
        // One for the signup, one for the single (deduplicated) cancel — never two.
        assertThat(entries).hasSize(2);
        assertThat(entries).filteredOn(entry -> "CANCELED".equals(entry.getNewState())).hasSize(1);
    }

    @Test
    void aRetryWithTheSameIdempotencyKeyAfterARejectedCancelSeesTheSameRejectionNotAFabricatedSuccess() {
        // Regression test: recordIfNew's insert commits independently (REQUIRES_NEW)
        // before Subscription.cancel() runs. If cancel() then throws (already
        // canceled, here), the key must be released — otherwise this exact retry,
        // reusing the same key, would find the key already recorded and be wrongly
        // short-circuited into a fabricated 200 instead of the same rejection.
        UUID freePlanId = planRepository.findByCode("free").orElseThrow().getId();
        SubscriptionSignupResult signup = subscriptionService.signUp(new SignupCommand(
                freePlanId, "idem-release-" + UUID.randomUUID() + "@example.com", null, false, null, "corr-it-31"));
        UUID customerId = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow().getCustomer().getId();
        // Pre-cancel (no idempotency key) so the Subscription is already CANCELED
        // before the key-bearing attempts below.
        subscriptionService.cancel(signup.subscriptionId(), customerId, null, "corr-it-32");
        String idempotencyKey = "idem-release-key-" + UUID.randomUUID();

        assertThatThrownBy(() -> subscriptionService.cancel(signup.subscriptionId(), customerId, idempotencyKey, "corr-it-33"))
                .isInstanceOf(SubscriptionAlreadyCanceledException.class);

        // Same key, same (still-invalid) request: must see the same rejection again,
        // not a short-circuited "success" reusing a key that never actually applied.
        assertThatThrownBy(() -> subscriptionService.cancel(signup.subscriptionId(), customerId, idempotencyKey, "corr-it-34"))
                .isInstanceOf(SubscriptionAlreadyCanceledException.class);
    }

    @Test
    void anExpiredIdempotencyKeyCanBeReusedThroughCancelsOwnAmbientTransactionWithNoHangOrFalseDuplicate() {
        // Regression test: the expired-key delete must commit (REQUIRES_NEW) before the
        // subsequent insert's own separate REQUIRES_NEW transaction runs. Both now run
        // from inside cancel()'s own @Transactional — the ambient transaction this bug
        // specifically depended on to manifest (a top-level call wouldn't have exposed
        // it, since each repository call would auto-commit on its own).
        UUID freePlanId = planRepository.findByCode("free").orElseThrow().getId();
        SubscriptionSignupResult signup = subscriptionService.signUp(new SignupCommand(
                freePlanId, "idem-expired-" + UUID.randomUUID() + "@example.com", null, false, null, "corr-it-35"));
        UUID customerId = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow().getCustomer().getId();
        String idempotencyKey = "idem-expired-key-" + UUID.randomUUID();
        Instant longAgo = Instant.now().minusSeconds(3600);
        idempotencyKeyRepository.saveAndFlush(new IdempotencyKeyRecord(
                UUID.randomUUID(), customerId, SubscriptionService.CANCEL_OPERATION, idempotencyKey,
                longAgo.minusSeconds(1), longAgo));

        SubscriptionView view = subscriptionService.cancel(signup.subscriptionId(), customerId, idempotencyKey, "corr-it-36");

        assertThat(view.state()).isEqualTo(SubscriptionState.CANCELED);
    }

    @Test
    void aStaleWriteAgainstAnAlreadyModifiedSubscriptionFailsWithOptimisticLocking() {
        // Simulates two concurrent cancel requests with no Idempotency-Key that both
        // loaded the Subscription before either committed: staleCopy stands in for the
        // second request's already-fetched, now-stale in-memory Subscription. Sequential
        // instead of real threads specifically to make this deterministic — Subscription's
        // @Version field is what must catch this, not luck in thread scheduling.
        UUID freePlanId = planRepository.findByCode("free").orElseThrow().getId();
        SubscriptionSignupResult signup = subscriptionService.signUp(new SignupCommand(
                freePlanId, "stale-write-" + UUID.randomUUID() + "@example.com", null, false, null, "corr-it-29"));
        UUID customerId = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow().getCustomer().getId();
        Subscription staleCopy = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow();

        subscriptionService.cancel(signup.subscriptionId(), customerId, null, "corr-it-30");

        staleCopy.cancel();
        assertThatThrownBy(() -> subscriptionRepository.saveAndFlush(staleCopy))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(signup.subscriptionId());
        // One for the signup, one for the single successful cancel — the stale write
        // never committed, so it never produced a second one.
        assertThat(entries).hasSize(2);
    }
}
