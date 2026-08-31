package com.subscriptionbilling.billingcore.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Write side of the Plan catalog, plus the Admin-shaped read: create,
 * retire, and reprice a Plan. Kept separate from {@link PlanCatalogService} — that
 * service is the public, signup-facing read path and stays read-only, per its own
 * Javadoc; this service owns the capability product-design.md §4 used to rule out
 * ("not a runtime/admin-managed feature") before narrowly reopened it.
 */
@Service
public class PlanAdministrationService {

    private static final Logger log = LoggerFactory.getLogger(PlanAdministrationService.class);

    private final PlanRepository planRepository;
    private final PriceVersionRepository priceVersionRepository;
    private final Clock clock;

    @Autowired
    public PlanAdministrationService(PlanRepository planRepository, PriceVersionRepository priceVersionRepository) {
        this(planRepository, priceVersionRepository, Clock.systemUTC());
    }

    PlanAdministrationService(PlanRepository planRepository, PriceVersionRepository priceVersionRepository, Clock clock) {
        this.planRepository = planRepository;
        this.priceVersionRepository = priceVersionRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AdminPlanSummary> listAll() {
        List<Plan> plans = planRepository.findAll();
        Instant now = Instant.now(clock);
        return plans.stream().map(plan -> toSummary(plan, now)).toList();
    }

    @Transactional(readOnly = true)
    public AdminPlanDetail getById(UUID planId) {
        Plan plan = planRepository.findById(planId).orElseThrow(() -> new PlanNotFoundException(planId));
        return toDetail(plan);
    }

    /**
     * @param code         must be unique across the whole catalog
     * @param name         display name
     * @param initialPrice this Plan's first {@link PriceVersion}, effective immediately
     * @throws PlanCodeAlreadyExistsException if {@code code} is already in use
     */
    @Transactional
    public AdminPlanDetail createPlan(String code, String name, BigDecimal initialPrice, String actorUsername,
                                       String correlationId) {
        if (planRepository.findByCode(code).isPresent()) {
            throw new PlanCodeAlreadyExistsException(code);
        }
        Plan plan = new Plan(UUID.randomUUID(), code, name);
        planRepository.save(plan);
        priceVersionRepository.save(new PriceVersion(UUID.randomUUID(), plan, initialPrice, Instant.now(clock)));
        log.info("Admin {} created Plan {} ({}) [correlationId={}]", actorUsername, plan.getId(), code, correlationId);
        return toDetail(plan);
    }

    /**
     * Idempotent: retiring an already-retired Plan is a no-op, not an error — same
     * reasoning as {@code Subscription#undoCancel}'s sibling operations treat a
     * repeated request as safe rather than a conflict, since "closed to new signups" has
     * no further state to move through.
     *
     * @throws PlanNotFoundException if {@code planId} doesn't exist
     */
    @Transactional
    public AdminPlanDetail retirePlan(UUID planId, String actorUsername, String correlationId) {
        Plan plan = planRepository.findById(planId).orElseThrow(() -> new PlanNotFoundException(planId));
        if (!plan.isRetiredForSignup()) {
            plan.retireForSignup();
            planRepository.save(plan);
            log.info("Admin {} retired Plan {} [correlationId={}]", actorUsername, planId, correlationId);
        }
        return toDetail(plan);
    }

    /**
     * Adds a new effective-dated price to a Plan's history. {@code effectiveFrom} must
     * come strictly after the Plan's current latest {@link PriceVersion} (scheduled or
     * already in effect) — see {@link InvalidPriceVersionException}'s Javadoc for why.
     *
     * @throws PlanNotFoundException        if {@code planId} doesn't exist
     * @throws InvalidPriceVersionException if {@code effectiveFrom} doesn't come after
     *         the Plan's current latest PriceVersion
     */
    @Transactional
    public AdminPlanDetail addPriceVersion(UUID planId, BigDecimal amount, Instant effectiveFrom,
                                            String actorUsername, String correlationId) {
        Plan plan = planRepository.findById(planId).orElseThrow(() -> new PlanNotFoundException(planId));
        Optional<PriceVersion> latest = priceVersionRepository.findTopByPlanIdOrderByEffectiveFromDesc(planId);
        if (latest.isPresent() && !effectiveFrom.isAfter(latest.get().getEffectiveFrom())) {
            throw new InvalidPriceVersionException(planId, effectiveFrom, latest.get().getEffectiveFrom());
        }
        priceVersionRepository.save(new PriceVersion(UUID.randomUUID(), plan, amount, effectiveFrom));
        log.info("Admin {} added a PriceVersion to Plan {} (amount={}, effectiveFrom={}) [correlationId={}]",
                actorUsername, planId, amount, effectiveFrom, correlationId);
        return toDetail(plan);
    }

    private AdminPlanSummary toSummary(Plan plan, Instant now) {
        BigDecimal currentPrice = priceVersionRepository
                .findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(plan.getId(), now)
                .map(PriceVersion::getAmount)
                .orElse(null);
        return new AdminPlanSummary(plan.getId(), plan.getCode(), plan.getName(), plan.isRetiredForSignup(), currentPrice);
    }

    private AdminPlanDetail toDetail(Plan plan) {
        List<AdminPlanDetail.PriceVersionEntry> history = priceVersionRepository.findByPlanId(plan.getId()).stream()
                .sorted(Comparator.comparing(PriceVersion::getEffectiveFrom))
                .map(AdminPlanDetail.PriceVersionEntry::from)
                .toList();
        return new AdminPlanDetail(plan.getId(), plan.getCode(), plan.getName(), plan.isRetiredForSignup(), history);
    }
}
