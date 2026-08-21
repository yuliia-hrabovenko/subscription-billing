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
}
