package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.plan.Plan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure domain-layer coverage of {@link Subscription#cancel()} and {@link
 * Subscription#undoCancel()} — every edge of the cancel/undo-cancel portion of the
 * state diagram, exercised directly against the entity with no repository, audit, or
 * HTTP concerns involved.
 */
class SubscriptionTest {

    private final Customer customer = new Customer(UUID.randomUUID(), "customer@example.com");
    private final Plan plan = new Plan(UUID.randomUUID(), "pro", "Pro");

    private Subscription subscriptionIn(SubscriptionState state) {
        return new Subscription(UUID.randomUUID(), customer, plan, state);
    }

    @Test
    void cancelFromTrialingTransitionsImmediatelyToCanceledAndClearsTrialEndsAt() {
        Subscription subscription = Subscription.startTrial(UUID.randomUUID(), customer, plan, Instant.now());

        subscription.cancel();

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.CANCELED);
        // A canceled Subscription never carries a trialEndsAt — see SubscriptionResponse's
        // documented contract that the field is set only while state is TRIALING.
        assertThat(subscription.getTrialEndsAt()).isNull();
    }

    @Test
    void cancelFromSuspendedTransitionsImmediatelyToCanceled() {
        Subscription subscription = subscriptionIn(SubscriptionState.SUSPENDED);

        subscription.cancel();

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.CANCELED);
    }

    @Test
    void cancelFromSuspendedClearsABillingCycleAnchorInheritedFromAnEarlierPaidActiveLife() {
        // A suspended Subscription can have reached this state from a paid active one
        // (renewal charge failed) and so still carry a billingCycleAnchor — no
        // production path builds this combination yet (Dunning owns suspend), but
        // cancel() must still clear it, same as trialEndsAt, so a canceled Subscription
        // never shows a Billing Cycle.
        Subscription subscription = new Subscription(
                UUID.randomUUID(), customer, plan, SubscriptionState.SUSPENDED, Instant.now());

        subscription.cancel();

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.CANCELED);
        assertThat(subscription.getBillingCycleAnchor()).isNull();
    }

    @Test
    void cancelFromSuspendedClearsTheDunningBillingPeriod() {
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, plan, Instant.now());
        subscription.suspend(LocalDate.of(2026, 8, 24));

        subscription.cancel();

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.CANCELED);
        assertThat(subscription.getDunningBillingPeriod()).isNull();
    }

    @Test
    void cancelFromActiveWithABillingCycleDefersToPendingCancellation() {
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, plan, Instant.now());

        subscription.cancel();

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.PENDING_CANCELLATION);
    }

    @Test
    void cancelFromActiveWithNoBillingCycleTransitionsImmediatelyToCanceled() {
        // A free-Plan Subscription is ACTIVE with no Billing Cycle (Invariant 5) — there's
        // no paid period to defer to, and nothing would ever advance it out of
        // pending_cancellation, so it must cancel immediately like trialing/suspended.
        Subscription subscription = subscriptionIn(SubscriptionState.ACTIVE);

        subscription.cancel();

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.CANCELED);
    }

    @Test
    void cancelFromPendingCancellationIsRejected() {
        Subscription subscription = subscriptionIn(SubscriptionState.PENDING_CANCELLATION);

        assertThatThrownBy(subscription::cancel).isInstanceOf(SubscriptionAlreadyPendingCancellationException.class);

        // Rejected transitions never mutate state — still exactly where it started.
        assertThat(subscription.getState()).isEqualTo(SubscriptionState.PENDING_CANCELLATION);
    }

    @Test
    void cancelFromCanceledIsRejected() {
        Subscription subscription = subscriptionIn(SubscriptionState.CANCELED);

        assertThatThrownBy(subscription::cancel).isInstanceOf(SubscriptionAlreadyCanceledException.class);

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.CANCELED);
    }

    @Test
    void cancelForDisputeFromActiveWithABillingCycleTransitionsImmediatelyToCanceledInsteadOfDeferring() {
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, plan, Instant.now());

        subscription.cancelForDispute();

        // Unlike cancel(), an open Billing Cycle never defers this to pending_cancellation.
        assertThat(subscription.getState()).isEqualTo(SubscriptionState.CANCELED);
        assertThat(subscription.getBillingCycleAnchor()).isNull();
    }

    @Test
    void cancelForDisputeFromActiveWithNoBillingCycleTransitionsImmediatelyToCanceled() {
        Subscription subscription = subscriptionIn(SubscriptionState.ACTIVE);

        subscription.cancelForDispute();

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.CANCELED);
    }

    @Test
    void cancelForDisputeFromSuspendedTransitionsImmediatelyToCanceledAndClearsTheDunningBillingPeriod() {
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, plan, Instant.now());
        subscription.suspend(LocalDate.of(2026, 8, 24));

        subscription.cancelForDispute();

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.CANCELED);
        assertThat(subscription.getDunningBillingPeriod()).isNull();
    }

    @Test
    void cancelForDisputeOnAnAlreadyCanceledSubscriptionIsASafeNoOp() {
        Subscription subscription = subscriptionIn(SubscriptionState.CANCELED);

        subscription.cancelForDispute();

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.CANCELED);
    }

    @ParameterizedTest
    @EnumSource(value = SubscriptionState.class, names = {"TRIALING", "PENDING_CANCELLATION"})
    void cancelForDisputeFromTrialingOrPendingCancellationIsRejected(SubscriptionState originatingState) {
        Subscription subscription = subscriptionIn(originatingState);

        assertThatThrownBy(subscription::cancelForDispute)
                .isInstanceOf(SubscriptionNotEligibleForDisputeCancellationException.class);

        // Rejected transitions never mutate state — still exactly where it started.
        assertThat(subscription.getState()).isEqualTo(originatingState);
    }

    @Test
    void undoCancelFromPendingCancellationRestoresActive() {
        Subscription subscription = subscriptionIn(SubscriptionState.PENDING_CANCELLATION);

        subscription.undoCancel();

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(value = SubscriptionState.class, names = "PENDING_CANCELLATION", mode = EnumSource.Mode.EXCLUDE)
    void undoCancelFromAnyOtherStateIsRejected(SubscriptionState originatingState) {
        Subscription subscription = subscriptionIn(originatingState);

        assertThatThrownBy(subscription::undoCancel).isInstanceOf(SubscriptionNotPendingCancellationException.class);

        assertThat(subscription.getState()).isEqualTo(originatingState);
    }

    @Test
    void schedulePlanChangeFromActiveWithABillingCycleSetsPendingPlanChangeAndLeavesTheCurrentPlanAndStateUnchanged() {
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, plan, Instant.now());
        Plan targetPlan = new Plan(UUID.randomUUID(), "enterprise", "Enterprise");

        subscription.schedulePlanChange(targetPlan, false, Instant.now());

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(subscription.getPlan()).isEqualTo(plan);
        assertThat(subscription.getPendingPlanChange()).isEqualTo(targetPlan);
    }

    @Test
    void schedulePlanChangeToFreeFromActiveWithABillingCycleSetsPendingPlanChangeWithoutCancelingTheSubscription() {
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, plan, Instant.now());
        Plan freePlan = new Plan(UUID.randomUUID(), "free", "Free");

        subscription.schedulePlanChange(freePlan, true, Instant.now());

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(subscription.getPlan()).isEqualTo(plan);
        assertThat(subscription.getPendingPlanChange()).isEqualTo(freePlan);
    }

    @Test
    void schedulingASecondPlanChangeWhileOneIsAlreadyPendingReplacesTheFirst() {
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, plan, Instant.now());
        Plan firstTarget = new Plan(UUID.randomUUID(), "enterprise", "Enterprise");
        Plan secondTarget = new Plan(UUID.randomUUID(), "free", "Free");
        subscription.schedulePlanChange(firstTarget, false, Instant.now());

        subscription.schedulePlanChange(secondTarget, true, Instant.now());

        assertThat(subscription.getPendingPlanChange()).isEqualTo(secondTarget);
    }

    @Test
    void schedulePlanChangeFromActiveWithNoBillingCycleAppliesImmediatelyAndOpensABillingCycleAnchoredToNow() {
        // A free-Plan Subscription is ACTIVE with no Billing Cycle — the one exception to
        // "next cycle": free-to-paid applies now instead of setting pendingPlanChange.
        Subscription subscription = subscriptionIn(SubscriptionState.ACTIVE);
        Plan targetPlan = new Plan(UUID.randomUUID(), "pro", "Pro");
        Instant now = Instant.now();

        subscription.schedulePlanChange(targetPlan, false,now);

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(subscription.getPlan()).isEqualTo(targetPlan);
        assertThat(subscription.getPendingPlanChange()).isNull();
        assertThat(subscription.getBillingCycleAnchor()).isEqualTo(now);
    }

    @ParameterizedTest
    @EnumSource(value = SubscriptionState.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void schedulePlanChangeFromAnyNonActiveStateIsRejected(SubscriptionState originatingState) {
        Subscription subscription = subscriptionIn(originatingState);
        Plan targetPlan = new Plan(UUID.randomUUID(), "pro", "Pro");

        assertThatThrownBy(() -> subscription.schedulePlanChange(targetPlan, false, Instant.now()))
                .isInstanceOf(SubscriptionNotEligibleForPlanChangeException.class);

        // Rejected transitions never mutate state — still exactly where it started.
        assertThat(subscription.getState()).isEqualTo(originatingState);
        assertThat(subscription.getPendingPlanChange()).isNull();
    }

    @Test
    void applyPendingPlanChangeToAStillPaidPlanSwitchesPlanClearsPendingAndLeavesTheBillingCycleUntouched() {
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, plan, Instant.now());
        Plan targetPlan = new Plan(UUID.randomUUID(), "enterprise", "Enterprise");
        subscription.schedulePlanChange(targetPlan, false, Instant.now());
        Instant anchorBeforeApplying = subscription.getBillingCycleAnchor();
        subscription.advanceDueDate(LocalDate.of(2026, 8, 24));

        subscription.applyPendingPlanChange(false);

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(subscription.getPlan()).isEqualTo(targetPlan);
        assertThat(subscription.getPendingPlanChange()).isNull();
        assertThat(subscription.getBillingCycleAnchor()).isEqualTo(anchorBeforeApplying);
        assertThat(subscription.getDueDate()).isEqualTo(LocalDate.of(2026, 8, 24));
    }

    @Test
    void applyPendingPlanChangeToAFreePlanSwitchesPlanAndClearsTheBillingCycleAndDueDate() {
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, plan, Instant.now());
        Plan freePlan = new Plan(UUID.randomUUID(), "free", "Free");
        subscription.schedulePlanChange(freePlan, true, Instant.now());
        subscription.advanceDueDate(LocalDate.of(2026, 8, 24));

        subscription.applyPendingPlanChange(true);

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(subscription.getPlan()).isEqualTo(freePlan);
        assertThat(subscription.getPendingPlanChange()).isNull();
        assertThat(subscription.getBillingCycleAnchor()).isNull();
        assertThat(subscription.getDueDate()).isNull();
    }

    @Test
    void applyPendingPlanChangeWithNothingPendingIsRejected() {
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, plan, Instant.now());

        assertThatThrownBy(() -> subscription.applyPendingPlanChange(false))
                .isInstanceOf(IllegalStateException.class);

        assertThat(subscription.getPlan()).isEqualTo(plan);
    }

    @Test
    void suspendFromTrialingTransitionsImmediatelyToSuspendedAndRemembersTheBillingPeriod() {
        Subscription subscription = Subscription.startTrial(UUID.randomUUID(), customer, plan, Instant.now());

        subscription.suspend(LocalDate.of(2026, 8, 24));

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.SUSPENDED);
        assertThat(subscription.getDunningBillingPeriod()).isEqualTo(LocalDate.of(2026, 8, 24));
    }

    @Test
    void suspendFromActiveTransitionsImmediatelyToSuspendedAndKeepsTheBillingCycleAnchor() {
        Instant billingCycleAnchor = Instant.now();
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, plan, billingCycleAnchor);

        subscription.suspend(LocalDate.of(2026, 8, 24));

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.SUSPENDED);
        // Unlike cancel(), suspend() must not clear the anchor: a later successful
        // Payment Attempt restores active without re-anchoring the Billing Cycle.
        assertThat(subscription.getBillingCycleAnchor()).isEqualTo(billingCycleAnchor);
    }

    @ParameterizedTest
    @EnumSource(value = SubscriptionState.class, names = {"TRIALING", "ACTIVE"}, mode = EnumSource.Mode.EXCLUDE)
    void suspendFromAnyOtherStateIsRejected(SubscriptionState originatingState) {
        Subscription subscription = subscriptionIn(originatingState);

        assertThatThrownBy(() -> subscription.suspend(LocalDate.of(2026, 8, 24)))
                .isInstanceOf(SubscriptionNotEligibleForSuspensionException.class);

        // Rejected transitions never mutate state — still exactly where it started.
        assertThat(subscription.getState()).isEqualTo(originatingState);
    }

    @Test
    void advanceDueDateReplacesTheCurrentDueDateWithTheGivenOne() {
        Subscription subscription = new Subscription(
                UUID.randomUUID(), customer, plan, SubscriptionState.ACTIVE, Instant.now(), LocalDate.of(2026, 1, 31));

        subscription.advanceDueDate(LocalDate.of(2026, 2, 28));

        assertThat(subscription.getDueDate()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void scheduleRetryMovesTheDueDateToTheGivenRetryDateWithoutChangingState() {
        Subscription subscription = new Subscription(
                UUID.randomUUID(), customer, plan, SubscriptionState.SUSPENDED, Instant.now(), LocalDate.of(2026, 1, 31));

        subscription.scheduleRetry(LocalDate.of(2026, 2, 1));

        assertThat(subscription.getDueDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(subscription.getState()).isEqualTo(SubscriptionState.SUSPENDED);
    }

    @Test
    void applySuccessfulChargeFromTrialingActivatesOpensTheBillingCycleAndClearsTheTrial() {
        Subscription subscription = Subscription.startTrial(UUID.randomUUID(), customer, plan, Instant.now());
        Instant chargedAt = Instant.parse("2026-08-24T03:00:00Z");

        subscription.applySuccessfulCharge(chargedAt, LocalDate.of(2026, 9, 24));

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(subscription.getBillingCycleAnchor()).isEqualTo(chargedAt);
        assertThat(subscription.getTrialEndsAt()).isNull();
        assertThat(subscription.getDueDate()).isEqualTo(LocalDate.of(2026, 9, 24));
    }

    @Test
    void applySuccessfulChargeFromActiveOnlyAdvancesDueDateLeavingStateAndAnchorUnchanged() {
        Instant originalAnchor = Instant.parse("2026-01-31T00:00:00Z");
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, plan, originalAnchor);

        subscription.applySuccessfulCharge(Instant.parse("2026-02-28T03:00:00Z"), LocalDate.of(2026, 3, 31));

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.ACTIVE);
        // An ordinary renewal never re-anchors the Billing Cycle -- only a Trial
        // conversion, which has no prior anchor, establishes one from chargedAt.
        assertThat(subscription.getBillingCycleAnchor()).isEqualTo(originalAnchor);
        assertThat(subscription.getDueDate()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    void applySuccessfulChargeFromSuspendedRecoversToActiveClearsTheDunningBillingPeriodAndKeepsTheAnchor() {
        Instant originalAnchor = Instant.parse("2026-01-24T00:00:00Z");
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, plan, originalAnchor);
        subscription.suspend(LocalDate.of(2026, 8, 24));

        subscription.applySuccessfulCharge(Instant.parse("2026-08-27T03:00:00Z"), LocalDate.of(2026, 9, 24));

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.ACTIVE);
        // A Dunning recovery never re-anchors the Billing Cycle either -- same as an
        // ordinary renewal, only a Trial conversion establishes a fresh anchor.
        assertThat(subscription.getBillingCycleAnchor()).isEqualTo(originalAnchor);
        assertThat(subscription.getDunningBillingPeriod()).isNull();
        assertThat(subscription.getDueDate()).isEqualTo(LocalDate.of(2026, 9, 24));
    }
}
