package com.subscriptionbilling.billingjob.anchor;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnchorDateTest {

    @Test
    void resolvesToTheAnchorDayWhenTheMonthIsLongEnoughToHoldIt() {
        AnchorDate anchorDate = new AnchorDate(31);

        assertThat(anchorDate.resolveFor(YearMonth.of(2027, 1))).isEqualTo(LocalDate.of(2027, 1, 31));
    }

    @Test
    void clampsToFebruary28InANonLeapYear() {
        AnchorDate anchorDate = new AnchorDate(31);

        assertThat(anchorDate.resolveFor(YearMonth.of(2027, 2))).isEqualTo(LocalDate.of(2027, 2, 28));
    }

    @Test
    void clampsToFebruary29InALeapYear() {
        AnchorDate anchorDate = new AnchorDate(31);

        assertThat(anchorDate.resolveFor(YearMonth.of(2028, 2))).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    void revertsToThe31stOnceA31DayMonthRecursAndReclampsForEachShorterMonthAfterThat() {
        AnchorDate anchorDate = new AnchorDate(31);

        assertThat(anchorDate.resolveFor(YearMonth.of(2027, 1))).isEqualTo(LocalDate.of(2027, 1, 31));
        assertThat(anchorDate.resolveFor(YearMonth.of(2027, 2))).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(anchorDate.resolveFor(YearMonth.of(2027, 3))).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(anchorDate.resolveFor(YearMonth.of(2027, 4))).isEqualTo(LocalDate.of(2027, 4, 30));
        assertThat(anchorDate.resolveFor(YearMonth.of(2027, 5))).isEqualTo(LocalDate.of(2027, 5, 31));
    }

    @Test
    void nextChainsFromOneBillingDateToTheFollowingMonthsBillingDateAcrossTheClampAndRevertSequence() {
        AnchorDate anchorDate = new AnchorDate(31);
        LocalDate janThirtyFirst = LocalDate.of(2027, 1, 31);

        LocalDate february = anchorDate.next(janThirtyFirst);
        LocalDate march = anchorDate.next(february);
        LocalDate april = anchorDate.next(march);

        assertThat(february).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(march).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(april).isEqualTo(LocalDate.of(2027, 4, 30));
    }

    @Test
    void ofDerivesTheAnchorDayFromAGivenDate() {
        assertThat(AnchorDate.of(LocalDate.of(2027, 6, 15)).dayOfMonth()).isEqualTo(15);
    }

    @Test
    void rejectsADayOfMonthBelow1() {
        assertThatThrownBy(() -> new AnchorDate(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsADayOfMonthAbove31() {
        assertThatThrownBy(() -> new AnchorDate(32)).isInstanceOf(IllegalArgumentException.class);
    }
}
