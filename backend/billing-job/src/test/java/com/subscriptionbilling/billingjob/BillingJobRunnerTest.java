package com.subscriptionbilling.billingjob;

import com.subscriptionbilling.billingjob.due.DueSubscriptionsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link BillingJobRunner}: it resolves today's date from its {@link
 * Clock} and returns exactly what {@link DueSubscriptionsPort} selects for it, invoked
 * directly with no scheduler/cron infrastructure involved. Whether the due-date
 * comparison itself is correct against real persisted Subscriptions is the port
 * implementation's own contract, covered where that implementation lives.
 */
@ExtendWith(MockitoExtension.class)
class BillingJobRunnerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);
    private static final Clock FIXED_CLOCK = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    @Mock
    private DueSubscriptionsPort dueSubscriptionsPort;

    @Test
    void runReturnsExactlyTheSubscriptionIdsThePortSelectsForToday() {
        UUID dueSubscriptionId = UUID.randomUUID();
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(dueSubscriptionId));

        List<UUID> selected = new BillingJobRunner(dueSubscriptionsPort, FIXED_CLOCK).run();

        assertThat(selected).containsExactly(dueSubscriptionId);
    }

    @Test
    void runReturnsAnEmptyListWhenNoSubscriptionIsDue() {
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of());

        List<UUID> selected = new BillingJobRunner(dueSubscriptionsPort, FIXED_CLOCK).run();

        assertThat(selected).isEmpty();
    }
}
