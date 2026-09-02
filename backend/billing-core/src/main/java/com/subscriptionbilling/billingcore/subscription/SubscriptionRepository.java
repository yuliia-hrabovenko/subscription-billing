package com.subscriptionbilling.billingcore.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    /**
     * Backs Invariant 1's application-layer check. Passing {@link
     * SubscriptionState#CANCELED} as {@code excludedState} is what makes a Customer
     * whose only prior Subscription is {@code canceled} free to sign up again.
     *
     * @param customerId    the Customer to check
     * @param excludedState a state to ignore when checking for an existing Subscription
     * @return true if a Subscription in a state other than {@code excludedState} exists
     *         for this Customer
     */
    boolean existsByCustomerIdAndStateNot(UUID customerId, SubscriptionState excludedState);

    /**
     * Backs {@code POST /api/v1/customers/login}: the Customer's current Subscription
     * (there is at most one non-{@code canceled} Subscription per Customer), used to
     * populate the frontend session the same way signup's response does. Empty if the
     * Customer has no non-canceled Subscription (e.g. their only Subscription was
     * canceled) — login still succeeds in that case, just with nothing to land on but
     * the plans page.
     *
     * @param customerId    the Customer to look up
     * @param excludedState a state to ignore, always {@link SubscriptionState#CANCELED}
     */
    Optional<Subscription> findFirstByCustomerIdAndStateNot(UUID customerId, SubscriptionState excludedState);

    /**
     * Backs the billing job's due-Subscription scan (ADR-0001). {@code <=}, never
     * {@code ==}: a Subscription several days overdue (a missed run) is still
     * selected, giving automatic catch-up with no separate backfill path. A null
     * {@link Subscription#getDueDate()} (never on a paid Plan, or not yet scheduled)
     * never matches.
     *
     * @param asOf the cutoff date
     * @return the ids of every Subscription with a due date on or before {@code asOf}
     */
    @Query("select s.id from Subscription s where s.dueDate <= :asOf")
    List<UUID> findDueSubscriptionIds(@Param("asOf") LocalDate asOf);

    /**
     * Backs the trial-ending-soon daily scan's candidate selection. {@code state} is
     * always passed as {@link SubscriptionState#TRIALING}: a Subscription that has since
     * converted or been canceled is no longer in that state, so it drops out on its own
     * with no separate exclusion needed. {@code trialEndingSoonNotifiedAt is null} is
     * this query's whole guard against re-selecting an already-notified Subscription on a
     * later run.
     *
     * @param state           always {@link SubscriptionState#TRIALING}
     * @param leadTimeCutoff  the latest {@code trialEndsAt} to select (now plus the lead
     *                        time)
     * @return the ids of every trialing Subscription approaching its Trial end that
     *         hasn't already been notified
     */
    @Query("select s.id from Subscription s where s.state = :state and s.trialEndsAt <= :leadTimeCutoff "
            + "and s.trialEndingSoonNotifiedAt is null")
    List<UUID> findTrialsEndingSoonIds(@Param("state") SubscriptionState state,
                                        @Param("leadTimeCutoff") Instant leadTimeCutoff);

    /**
     * Backs an Admin customer detail view: every Subscription a Customer has
     * ever had, not just their current one — a Customer may have several over their
     * lifetime, and an Admin's visibility isn't limited to the current one
     * the way a Customer's own "at most one active" constraint is. Unbounded/no
     * pagination: this list is naturally small per Customer.
     */
    List<Subscription> findByCustomerId(UUID customerId);
}
