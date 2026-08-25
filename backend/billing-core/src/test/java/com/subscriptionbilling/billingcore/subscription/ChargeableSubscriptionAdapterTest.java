package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PriceVersion;
import com.subscriptionbilling.billingcore.plan.PriceVersionRepository;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link ChargeableSubscriptionAdapter}: it assembles a {@link
 * ChargeableSubscription} from the persisted Subscription's Customer/Plan and the
 * PriceVersion in effect for that Plan on the Billing Cycle date being charged (not
 * "now" — a several-days-overdue catch-up charge must still bill at the price in effect
 * on its own billing period). Whether the underlying repository queries are correct
 * against real data is each repository's own contract.
 */
@ExtendWith(MockitoExtension.class)
class ChargeableSubscriptionAdapterTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private PriceVersionRepository priceVersionRepository;

    private final Customer customer = new Customer(UUID.randomUUID(), "charge-me@example.com");
    private final Plan plan = new Plan(UUID.randomUUID(), "pro", "Pro");

    @Test
    void assemblesThePaymentTokenBillingPeriodPriceAndAnchorDayFromThePersistedSubscription() {
        customer.setPaymentMethodToken("tok_visa");
        // Jan-31 anchored -- the fixed day-of-month a later cycle's clamping derives from.
        Instant anchor = Instant.parse("2026-01-31T00:00:00Z");
        LocalDate dueDate = LocalDate.of(2026, 7, 31);
        Subscription subscription = new Subscription(
                UUID.randomUUID(), customer, plan, SubscriptionState.ACTIVE, anchor, dueDate);
        PriceVersion priceVersion = new PriceVersion(
                UUID.randomUUID(), plan, new BigDecimal("19.00"), Instant.parse("2026-01-01T00:00:00Z"));
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));
        when(priceVersionRepository.findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                plan.getId(), dueDate.atStartOfDay(ZoneOffset.UTC).toInstant())).thenReturn(Optional.of(priceVersion));

        ChargeableSubscription result = new ChargeableSubscriptionAdapter(subscriptionRepository, priceVersionRepository)
                .loadForCharge(subscription.getId());

        assertThat(result.subscriptionId()).isEqualTo(subscription.getId());
        assertThat(result.paymentMethodToken()).isEqualTo("tok_visa");
        assertThat(result.amount()).isEqualByComparingTo("19.00");
        assertThat(result.priceVersionId()).isEqualTo(priceVersion.getId());
        assertThat(result.billingPeriod()).isEqualTo(dueDate);
        assertThat(result.anchorDayOfMonth()).isEqualTo(31);
    }

    @Test
    void resolvesThePriceAsOfTheBillingPeriodBeingChargedRatherThanTheCurrentDate() {
        // A catch-up charge for a Subscription several days overdue must still bill at
        // the price in effect on its own (earlier) billing period, not at whatever price
        // took effect later, before the missed run was caught up.
        LocalDate overdueBillingPeriod = LocalDate.of(2026, 1, 20);
        Subscription subscription = new Subscription(
                UUID.randomUUID(), customer, plan, SubscriptionState.ACTIVE,
                Instant.parse("2026-01-20T00:00:00Z"), overdueBillingPeriod);
        PriceVersion priceInEffectOnTheBillingPeriod = new PriceVersion(
                UUID.randomUUID(), plan, new BigDecimal("19.00"), Instant.parse("2026-01-01T00:00:00Z"));
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));
        when(priceVersionRepository.findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                plan.getId(), overdueBillingPeriod.atStartOfDay(ZoneOffset.UTC).toInstant()))
                .thenReturn(Optional.of(priceInEffectOnTheBillingPeriod));

        ChargeableSubscription result = new ChargeableSubscriptionAdapter(subscriptionRepository, priceVersionRepository)
                .loadForCharge(subscription.getId());

        assertThat(result.priceVersionId()).isEqualTo(priceInEffectOnTheBillingPeriod.getId());
    }

    @Test
    void resolvesTheAnchorDayFromTodayNotFromAnExistingAnchorForATrialingSubscriptionConvertingNow() {
        // A trialing Subscription has no Billing Cycle yet (Invariant 5) -- there is no
        // existing anchor to derive a day-of-month from, so it must come from today,
        // the day this same charge is about to open the first Billing Cycle on.
        customer.setPaymentMethodToken("tok_visa");
        LocalDate trialEndDate = LocalDate.of(2026, 8, 20);
        Subscription subscription = Subscription.startTrial(UUID.randomUUID(), customer, plan, Instant.now());
        subscription.advanceDueDate(trialEndDate);
        PriceVersion priceVersion = new PriceVersion(
                UUID.randomUUID(), plan, new BigDecimal("19.00"), Instant.parse("2026-01-01T00:00:00Z"));
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));
        when(priceVersionRepository.findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                plan.getId(), trialEndDate.atStartOfDay(ZoneOffset.UTC).toInstant())).thenReturn(Optional.of(priceVersion));
        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-24T03:00:00Z"), ZoneOffset.UTC);

        ChargeableSubscription result = new ChargeableSubscriptionAdapter(subscriptionRepository, priceVersionRepository, fixedClock)
                .loadForCharge(subscription.getId());

        assertThat(result.billingPeriod()).isEqualTo(trialEndDate);
        assertThat(result.anchorDayOfMonth()).isEqualTo(24);
    }

    @Test
    void failsFastWhenTheSubscriptionDoesNotExist() {
        UUID subscriptionId = UUID.randomUUID();
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new ChargeableSubscriptionAdapter(subscriptionRepository, priceVersionRepository)
                .loadForCharge(subscriptionId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastWhenThePlanHasNoPriceVersionInEffect() {
        LocalDate dueDate = LocalDate.of(2026, 7, 31);
        Subscription subscription = new Subscription(
                UUID.randomUUID(), customer, plan, SubscriptionState.ACTIVE,
                Instant.parse("2026-01-31T00:00:00Z"), dueDate);
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));
        when(priceVersionRepository.findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                plan.getId(), dueDate.atStartOfDay(ZoneOffset.UTC).toInstant())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new ChargeableSubscriptionAdapter(subscriptionRepository, priceVersionRepository)
                .loadForCharge(subscription.getId()))
                .isInstanceOf(IllegalStateException.class);
    }
}
