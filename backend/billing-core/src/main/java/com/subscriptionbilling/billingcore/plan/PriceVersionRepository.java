package com.subscriptionbilling.billingcore.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PriceVersionRepository extends JpaRepository<PriceVersion, UUID> {

    List<PriceVersion> findByPlanId(UUID planId);

    /**
     * Every PriceVersion across {@code planIds} that has already taken effect as of
     * {@code asOf} — one query for the whole catalog rather than one per Plan, since the
     * caller (PlanCatalogService) needs this for every Plan it lists. The caller picks
     * the most recent one per Plan from the result.
     */
    List<PriceVersion> findByPlanIdInAndEffectiveFromLessThanEqual(Collection<UUID> planIds, Instant asOf);

    /**
     * The single PriceVersion in effect for one Plan as of {@code asOf} — unlike {@link
     * #findByPlanIdInAndEffectiveFromLessThanEqual}, this considers a retired Plan too
     * (billing a Subscription already on it must still resolve a price, per Invariant
     * 10), so it cannot reuse {@code PlanCatalogService}'s signup-eligibility path.
     */
    Optional<PriceVersion> findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(UUID planId, Instant asOf);
}
