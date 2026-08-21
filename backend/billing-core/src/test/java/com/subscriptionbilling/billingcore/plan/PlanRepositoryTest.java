package com.subscriptionbilling.billingcore.plan;

import com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PlanRepositoryTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PriceVersionRepository priceVersionRepository;

    @Test
    void savesAndFetchesAPlan() {
        Plan plan = new Plan(UUID.randomUUID(), "basic", "Basic");

        planRepository.saveAndFlush(plan);

        Optional<Plan> found = planRepository.findById(plan.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo("basic");
        assertThat(found.get().getName()).isEqualTo("Basic");
        assertThat(found.get().isRetiredForSignup()).isFalse();
    }

    @Test
    void seedMigrationProducesAFreeAndAPaidPlanEachWithAPriceVersion() {
        Plan free = planRepository.findByCode("free").orElseThrow();
        Plan pro = planRepository.findByCode("pro").orElseThrow();

        assertThat(free.isRetiredForSignup()).isFalse();
        assertThat(pro.isRetiredForSignup()).isFalse();
        assertThat(priceVersionRepository.findByPlanId(free.getId())).isNotEmpty();
        assertThat(priceVersionRepository.findByPlanId(pro.getId())).isNotEmpty();
    }
}
