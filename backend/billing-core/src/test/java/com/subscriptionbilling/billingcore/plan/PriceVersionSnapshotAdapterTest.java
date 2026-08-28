package com.subscriptionbilling.billingcore.plan;

import com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest;
import com.subscriptionbilling.billingjob.invoicing.PriceVersionSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Invariant 9 at this seam: once a receipt has been described from a PriceVersion
 * id, a later Plan price change must never alter what that same id describes.
 */
@DataJpaTest
@Import(PriceVersionSnapshotAdapter.class)
class PriceVersionSnapshotAdapterTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PriceVersionRepository priceVersionRepository;

    @Autowired
    private PriceVersionSnapshotAdapter priceVersionSnapshotAdapter;

    @Test
    void describesThePriceVersionChargedEvenAfterThePlansPriceHasSinceChanged() {
        Plan plan = planRepository.saveAndFlush(new Plan(UUID.randomUUID(), "enterprise", "Enterprise"));
        PriceVersion chargedPriceVersion = priceVersionRepository.saveAndFlush(
                new PriceVersion(UUID.randomUUID(), plan, new BigDecimal("49.00"), Instant.parse("2026-01-01T00:00:00Z")));

        // The Plan's price changes after the charge -- a new PriceVersion row, not a
        // mutation of the one already charged.
        priceVersionRepository.saveAndFlush(
                new PriceVersion(UUID.randomUUID(), plan, new BigDecimal("79.00"), Instant.parse("2026-09-01T00:00:00Z")));

        PriceVersionSnapshot snapshot = priceVersionSnapshotAdapter.describe(chargedPriceVersion.getId());

        assertThat(snapshot.planName()).isEqualTo("Enterprise");
        assertThat(snapshot.amount()).isEqualByComparingTo("49.00");
    }
}
