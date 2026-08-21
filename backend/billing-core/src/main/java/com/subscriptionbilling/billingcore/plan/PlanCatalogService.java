package com.subscriptionbilling.billingcore.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Application-layer read path for the Plan catalog. Joins Plan and PriceVersion — two
 * entities this module owns — into {@link PlanSummary}, the read model other modules are
 * allowed to depend on. "Current price" (which PriceVersion is in effect right now) is a
 * business rule of the Plan's price history.
 */
@Service
@Transactional(readOnly = true)
public class PlanCatalogService {

    private static final Logger log = LoggerFactory.getLogger(PlanCatalogService.class);

    private final PlanRepository planRepository;
    private final PriceVersionRepository priceVersionRepository;
    private final Clock clock;

    @Autowired
    public PlanCatalogService(PlanRepository planRepository, PriceVersionRepository priceVersionRepository) {
        this(planRepository, priceVersionRepository, Clock.systemUTC());
    }

    PlanCatalogService(PlanRepository planRepository, PriceVersionRepository priceVersionRepository, Clock clock) {
        this.planRepository = planRepository;
        this.priceVersionRepository = priceVersionRepository;
        this.clock = clock;
    }

    /**
     * Plans open for new signups. A Plan with {@code retiredForSignup} set
     * is excluded here, though it remains valid for Subscriptions already on it.
     */
    public List<PlanSummary> listAvailablePlans() {
        List<Plan> plans = planRepository.findByRetiredForSignupFalse();
        if (plans.isEmpty()) {
            return List.of();
        }

        Map<UUID, PriceVersion> currentPriceByPlanId = currentPricesByPlanId(plans, Instant.now(clock));

        return plans.stream()
                .flatMap(plan -> toSummary(plan, currentPriceByPlanId).stream())
                .toList();
    }

    /**
     * A single Plan, subject to the exact same signup-eligibility rule {@link
     * #listAvailablePlans()} applies to the whole catalog (not retired, has a current
     * price) — so a Plan absent from the public listing can never be selected directly
     * by ID either. Collapses "doesn't exist," "retired," and "no PriceVersion yet" into
     * one empty result: from a signup request's point of view they're the same outcome.
     *
     * <p>Reuses {@link #currentPricesByPlanId}/{@link #toSummary} rather than its own
     * "which PriceVersion is current" query, so this and {@link #listAvailablePlans()}
     * can never resolve a Plan's current price differently from each other.
     */
    public Optional<PlanSummary> findAvailablePlan(UUID planId) {
        Plan plan = planRepository.findById(planId).orElse(null);
        if (plan == null || plan.isRetiredForSignup()) {
            return Optional.empty();
        }
        return toSummary(plan, currentPricesByPlanId(List.of(plan), Instant.now(clock)));
    }

    /**
     * One query for the whole batch instead of one per Plan: the catalog is small today,
     * but a public, unauthenticated, likely-high-traffic endpoint shouldn't scale its
     * query count with the number of Plans on offer.
     */
    private Map<UUID, PriceVersion> currentPricesByPlanId(List<Plan> plans, Instant now) {
        return priceVersionRepository
                .findByPlanIdInAndEffectiveFromLessThanEqual(plans.stream().map(Plan::getId).toList(), now)
                .stream()
                .collect(Collectors.toMap(
                        priceVersion -> priceVersion.getPlan().getId(),
                        Function.identity(),
                        (a, b) -> a.getEffectiveFrom().isAfter(b.getEffectiveFrom()) ? a : b));
    }

    private Optional<PlanSummary> toSummary(Plan plan, Map<UUID, PriceVersion> currentPriceByPlanId) {
        PriceVersion currentPrice = currentPriceByPlanId.get(plan.getId());
        if (currentPrice == null) {
            // A data problem with one Plan (e.g. a PriceVersion not yet committed) must
            // not take down the whole public catalog for every other, perfectly valid
            // Plan — so this Plan is dropped from the listing rather than failing the
            // request.
            log.warn("Plan {} has no PriceVersion in effect; excluding it from the catalog", plan.getCode());
            return Optional.empty();
        }
        return Optional.of(new PlanSummary(plan.getId(), plan.getCode(), plan.getName(), currentPrice.getAmount()));
    }
}
