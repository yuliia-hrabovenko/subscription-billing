package com.subscriptionbilling.billingcore.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanCatalogServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private PlanRepository planRepository;

    @Mock
    private PriceVersionRepository priceVersionRepository;

    @Test
    void listsOnlyNonRetiredPlansWithTheirCurrentPrice() {
        Plan free = new Plan(UUID.randomUUID(), "free", "Free");
        Plan pro = new Plan(UUID.randomUUID(), "pro", "Pro");
        when(planRepository.findByRetiredForSignupFalse()).thenReturn(List.of(free, pro));
        when(priceVersionRepository.findByPlanIdInAndEffectiveFromLessThanEqual(any(), any())).thenReturn(List.of(
                priceVersion(free, "0.00", NOW.minusSeconds(60)),
                priceVersion(pro, "19.00", NOW.minusSeconds(60))));
        PlanCatalogService service = new PlanCatalogService(planRepository, priceVersionRepository, FIXED_CLOCK);

        List<PlanSummary> catalog = service.listAvailablePlans();

        assertThat(catalog).containsExactlyInAnyOrder(
                new PlanSummary(free.getId(), "free", "Free", new BigDecimal("0.00")),
                new PlanSummary(pro.getId(), "pro", "Pro", new BigDecimal("19.00")));
    }

    @Test
    void aRetiredPlanIsNeverAskedForBecauseTheRepositoryQueryAlreadyExcludesIt() {
        when(planRepository.findByRetiredForSignupFalse()).thenReturn(List.of());
        PlanCatalogService service = new PlanCatalogService(planRepository, priceVersionRepository, FIXED_CLOCK);

        List<PlanSummary> catalog = service.listAvailablePlans();

        assertThat(catalog).isEmpty();
        // An empty Plan list has nothing to price, so the batched price query — which
        // would otherwise be a wasted round trip — is skipped entirely.
        verify(priceVersionRepository, never()).findByPlanIdInAndEffectiveFromLessThanEqual(any(), any());
    }

    @Test
    void picksTheMostRecentOfSeveralPriceVersionsThatHaveAlreadyTakenEffect() {
        Plan pro = new Plan(UUID.randomUUID(), "pro", "Pro");
        when(planRepository.findByRetiredForSignupFalse()).thenReturn(List.of(pro));
        // Simulates a Plan with price history: two PriceVersions already in effect: the
        // service — not the query — must pick the one with the latest effectiveFrom.
        when(priceVersionRepository.findByPlanIdInAndEffectiveFromLessThanEqual(any(), any())).thenReturn(List.of(
                priceVersion(pro, "19.00", NOW.minusSeconds(120)),
                priceVersion(pro, "24.00", NOW.minusSeconds(1))));
        PlanCatalogService service = new PlanCatalogService(planRepository, priceVersionRepository, FIXED_CLOCK);

        List<PlanSummary> catalog = service.listAvailablePlans();

        assertThat(catalog).singleElement().extracting(PlanSummary::currentPrice)
                .isEqualTo(new BigDecimal("24.00"));
    }

    @Test
    void aPlanWithNoPriceVersionInEffectIsExcludedRatherThanFailingTheWholeCatalog() {
        Plan pro = new Plan(UUID.randomUUID(), "pro", "Pro");
        Plan broken = new Plan(UUID.randomUUID(), "broken", "Broken");
        when(planRepository.findByRetiredForSignupFalse()).thenReturn(List.of(pro, broken));
        // Only "pro" has a PriceVersion in the batch result; "broken" has none.
        when(priceVersionRepository.findByPlanIdInAndEffectiveFromLessThanEqual(any(), any()))
                .thenReturn(List.of(priceVersion(pro, "19.00", NOW.minusSeconds(60))));
        PlanCatalogService service = new PlanCatalogService(planRepository, priceVersionRepository, FIXED_CLOCK);

        List<PlanSummary> catalog = service.listAvailablePlans();

        assertThat(catalog).extracting(PlanSummary::code).containsExactly("pro");
    }

    private static PriceVersion priceVersion(Plan plan, String amount, Instant effectiveFrom) {
        return new PriceVersion(UUID.randomUUID(), plan, new BigDecimal(amount), effectiveFrom);
    }
}
