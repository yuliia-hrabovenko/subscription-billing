package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.plan.Plan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
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
}
