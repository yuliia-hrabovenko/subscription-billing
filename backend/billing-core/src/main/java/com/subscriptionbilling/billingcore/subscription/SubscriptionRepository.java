package com.subscriptionbilling.billingcore.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
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
}
