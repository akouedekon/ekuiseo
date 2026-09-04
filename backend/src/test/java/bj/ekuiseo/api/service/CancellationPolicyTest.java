package bj.ekuiseo.api.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CancellationPolicyTest {

    private final CancellationPolicy policy = new CancellationPolicy();

    @Test
    void moreThan24hBeforeDeparture_isFullyRefunded() {
        Instant departure = Instant.now().plus(48, ChronoUnit.HOURS);
        Instant now = Instant.now();
        CancellationPolicy.Outcome outcome = policy.evaluate(10_000, now, departure);
        assertThat(outcome.refundAmount()).isEqualTo(10_000);
        assertThat(outcome.retainedAmount()).isZero();
    }

    @Test
    void exactlyAtTheTwentyFourHourBoundary_isStillFree() {
        Instant now = Instant.now();
        Instant departure = now.plus(24, ChronoUnit.HOURS).plusSeconds(1);
        CancellationPolicy.Outcome outcome = policy.evaluate(10_000, now, departure);
        assertThat(outcome.retainedAmount()).isZero();
    }

    @Test
    void lessThan24hBeforeDeparture_halfIsRetained() {
        Instant departure = Instant.now().plus(2, ChronoUnit.HOURS);
        Instant now = Instant.now();
        CancellationPolicy.Outcome outcome = policy.evaluate(10_000, now, departure);
        assertThat(outcome.refundAmount()).isEqualTo(5_000);
        assertThat(outcome.retainedAmount()).isEqualTo(5_000);
    }

    @Test
    void afterDepartureTime_nothingIsRefunded() {
        Instant departure = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant now = Instant.now();
        CancellationPolicy.Outcome outcome = policy.evaluate(10_000, now, departure);
        assertThat(outcome.refundAmount()).isZero();
        assertThat(outcome.retainedAmount()).isEqualTo(10_000);
    }

    @Test
    void oddAmount_halfRetainedRoundsDown_refundGetsTheRemainder() {
        Instant departure = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant now = Instant.now();
        CancellationPolicy.Outcome outcome = policy.evaluate(101, now, departure);
        assertThat(outcome.retainedAmount()).isEqualTo(50);
        assertThat(outcome.refundAmount()).isEqualTo(51);
        assertThat(outcome.refundAmount() + outcome.retainedAmount()).isEqualTo(101);
    }
}
