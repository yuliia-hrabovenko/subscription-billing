package com.subscriptionbilling.dunning;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DunningScheduleTest {

    private final Instant initialFailureAt = Instant.parse("2027-01-01T10:00:00Z");
    private final DunningSchedule schedule = new DunningSchedule(initialFailureAt);

    @Test
    void computesTheDayOneRetryAsExactlyOneDayAfterTheInitialFailure() {
        assertThat(schedule.dayOneRetryAt()).isEqualTo(initialFailureAt.plus(1, ChronoUnit.DAYS));
    }

    @Test
    void computesTheDayThreeRetryAsExactlyThreeDaysAfterTheInitialFailure() {
        assertThat(schedule.dayThreeRetryAt()).isEqualTo(initialFailureAt.plus(3, ChronoUnit.DAYS));
    }

    @Test
    void computesTheDaySevenRetryAsExactlySevenDaysAfterTheInitialFailure() {
        assertThat(schedule.daySevenRetryAt()).isEqualTo(initialFailureAt.plus(7, ChronoUnit.DAYS));
    }
}
