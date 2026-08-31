package com.subscriptionbilling.billingcore.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanAdministrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private PlanRepository planRepository;

    @Mock
    private PriceVersionRepository priceVersionRepository;

    @Test
    void createsAPlanWithAnImmediatelyEffectivePriceVersion() {
        PlanAdministrationService service = newService();
        when(planRepository.findByCode("enterprise")).thenReturn(Optional.empty());

        AdminPlanDetail detail = service.createPlan(
                "enterprise", "Enterprise", new BigDecimal("99.00"), "admin", "corr-1");

        assertThat(detail.code()).isEqualTo("enterprise");
        assertThat(detail.name()).isEqualTo("Enterprise");
        assertThat(detail.retiredForSignup()).isFalse();
        ArgumentCaptor<PriceVersion> priceVersionCaptor = ArgumentCaptor.forClass(PriceVersion.class);
        verify(priceVersionRepository).save(priceVersionCaptor.capture());
        assertThat(priceVersionCaptor.getValue().getAmount()).isEqualTo(new BigDecimal("99.00"));
        assertThat(priceVersionCaptor.getValue().getEffectiveFrom()).isEqualTo(NOW);
    }

    @Test
    void rejectsCreatingAPlanWithACodeThatAlreadyExists() {
        PlanAdministrationService service = newService();
        when(planRepository.findByCode("pro")).thenReturn(Optional.of(new Plan(UUID.randomUUID(), "pro", "Pro")));

        assertThatThrownBy(() -> service.createPlan("pro", "Pro Duplicate", BigDecimal.TEN, "admin", "corr-1"))
                .isInstanceOf(PlanCodeAlreadyExistsException.class);
        verify(priceVersionRepository, never()).save(any());
    }

    @Test
    void retiringAnAlreadyRetiredPlanIsANoOpRatherThanAnError() {
        PlanAdministrationService service = newService();
        Plan plan = new Plan(UUID.randomUUID(), "legacy", "Legacy");
        plan.retireForSignup();
        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(priceVersionRepository.findByPlanId(plan.getId())).thenReturn(List.of());

        AdminPlanDetail detail = service.retirePlan(plan.getId(), "admin", "corr-1");

        assertThat(detail.retiredForSignup()).isTrue();
        verify(planRepository, never()).save(any());
    }

    @Test
    void retiringAnActivePlanSavesTheRetiredFlag() {
        PlanAdministrationService service = newService();
        Plan plan = new Plan(UUID.randomUUID(), "pro", "Pro");
        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(priceVersionRepository.findByPlanId(plan.getId())).thenReturn(List.of());

        AdminPlanDetail detail = service.retirePlan(plan.getId(), "admin", "corr-1");

        assertThat(detail.retiredForSignup()).isTrue();
        verify(planRepository).save(plan);
    }

    @Test
    void retiringAPlanThatDoesNotExistThrowsNotFound() {
        PlanAdministrationService service = newService();
        UUID missing = UUID.randomUUID();
        when(planRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retirePlan(missing, "admin", "corr-1"))
                .isInstanceOf(PlanNotFoundException.class);
    }

    @Test
    void addsAPriceVersionAfterTheCurrentLatestOne() {
        PlanAdministrationService service = newService();
        Plan plan = new Plan(UUID.randomUUID(), "pro", "Pro");
        PriceVersion existing = new PriceVersion(UUID.randomUUID(), plan, new BigDecimal("19.00"), NOW.minusSeconds(60));
        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(priceVersionRepository.findTopByPlanIdOrderByEffectiveFromDesc(plan.getId())).thenReturn(Optional.of(existing));
        when(priceVersionRepository.findByPlanId(plan.getId())).thenReturn(List.of(existing));

        Instant newEffectiveFrom = NOW.plusSeconds(3600);
        service.addPriceVersion(plan.getId(), new BigDecimal("24.00"), newEffectiveFrom, "admin", "corr-1");

        ArgumentCaptor<PriceVersion> priceVersionCaptor = ArgumentCaptor.forClass(PriceVersion.class);
        verify(priceVersionRepository).save(priceVersionCaptor.capture());
        assertThat(priceVersionCaptor.getValue().getEffectiveFrom()).isEqualTo(newEffectiveFrom);
        assertThat(priceVersionCaptor.getValue().getAmount()).isEqualTo(new BigDecimal("24.00"));
    }

    @Test
    void rejectsAPriceVersionThatDoesNotComeAfterTheCurrentLatestOne() {
        PlanAdministrationService service = newService();
        Plan plan = new Plan(UUID.randomUUID(), "pro", "Pro");
        PriceVersion existing = new PriceVersion(UUID.randomUUID(), plan, new BigDecimal("19.00"), NOW);
        when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(priceVersionRepository.findTopByPlanIdOrderByEffectiveFromDesc(plan.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.addPriceVersion(plan.getId(), new BigDecimal("24.00"), NOW, "admin", "corr-1"))
                .isInstanceOf(InvalidPriceVersionException.class);
        assertThatThrownBy(() -> service.addPriceVersion(
                plan.getId(), new BigDecimal("24.00"), NOW.minusSeconds(1), "admin", "corr-1"))
                .isInstanceOf(InvalidPriceVersionException.class);
        verify(priceVersionRepository, never()).save(any());
    }

    private PlanAdministrationService newService() {
        return new PlanAdministrationService(planRepository, priceVersionRepository, FIXED_CLOCK);
    }
}
