package com.subscriptionbilling.billingcore.plan;

import com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PriceVersionRepositoryTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PriceVersionRepository priceVersionRepository;

    @Test
    void savesAndFetchesAPriceVersion() {
        Plan plan = planRepository.saveAndFlush(new Plan(UUID.randomUUID(), "enterprise", "Enterprise"));
        PriceVersion priceVersion = new PriceVersion(UUID.randomUUID(), plan, new BigDecimal("99.0000"), Instant.parse("2026-01-01T00:00:00Z"));

        priceVersionRepository.saveAndFlush(priceVersion);

        Optional<PriceVersion> found = priceVersionRepository.findById(priceVersion.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getAmount()).isEqualByComparingTo("99.0000");
        assertThat(found.get().getPlan().getId()).isEqualTo(plan.getId());
    }

    @Test
    void findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDescReturnsTheMostRecentEffectivePrice() {
        Plan plan = planRepository.saveAndFlush(new Plan(UUID.randomUUID(), "billed-plan", "Billed Plan"));
        PriceVersion original = priceVersionRepository.saveAndFlush(
                new PriceVersion(UUID.randomUUID(), plan, new BigDecimal("19.00"), Instant.parse("2026-01-01T00:00:00Z")));
        PriceVersion increase = priceVersionRepository.saveAndFlush(
                new PriceVersion(UUID.randomUUID(), plan, new BigDecimal("25.00"), Instant.parse("2026-06-01T00:00:00Z")));

        Optional<PriceVersion> beforeTheIncrease = priceVersionRepository
                .findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(plan.getId(), Instant.parse("2026-03-01T00:00:00Z"));
        Optional<PriceVersion> afterTheIncrease = priceVersionRepository
                .findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(plan.getId(), Instant.parse("2026-07-01T00:00:00Z"));

        assertThat(beforeTheIncrease).contains(original);
        assertThat(afterTheIncrease).contains(increase);
    }

    @Test
    void findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDescConsidersARetiredPlanToo() {
        // Billing an existing Subscription must still resolve a price for a Plan no
        // longer open to new signups (Invariant 10) -- unlike PlanCatalogService's
        // signup-eligibility path, this query has no retirement filter.
        Plan retired = new Plan(UUID.randomUUID(), "retired-billed-plan", "Retired Billed Plan");
        retired.retireForSignup();
        planRepository.saveAndFlush(retired);
        PriceVersion priceVersion = priceVersionRepository.saveAndFlush(
                new PriceVersion(UUID.randomUUID(), retired, new BigDecimal("19.00"), Instant.parse("2026-01-01T00:00:00Z")));

        Optional<PriceVersion> found = priceVersionRepository
                .findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(retired.getId(), Instant.parse("2026-08-01T00:00:00Z"));

        assertThat(found).contains(priceVersion);
    }
}
