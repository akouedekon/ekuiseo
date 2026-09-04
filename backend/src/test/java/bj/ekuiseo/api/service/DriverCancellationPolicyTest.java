package bj.ekuiseo.api.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DriverCancellationPolicyTest {

    private final DriverCancellationPolicy policy = new DriverCancellationPolicy();

    @Test
    void moreThan24hBeforeDeparture_isNotLate() {
        Instant now = Instant.now();
        Instant departure = now.plus(48, ChronoUnit.HOURS);
        assertThat(policy.isLate(now, departure)).isFalse();
    }

    @Test
    void lessThan24hBeforeDeparture_isLate() {
        Instant now = Instant.now();
        Instant departure = now.plus(2, ChronoUnit.HOURS);
        assertThat(policy.isLate(now, departure)).isTrue();
    }

    @Test
    void afterDepartureTime_isLate() {
        Instant now = Instant.now();
        Instant departure = now.minus(1, ChronoUnit.HOURS);
        assertThat(policy.isLate(now, departure)).isTrue();
    }

    @Test
    void exactlyAtTheTwentyFourHourBoundary_isNotYetLate() {
        Instant now = Instant.now();
        Instant departure = now.plus(24, ChronoUnit.HOURS).plusSeconds(1);
        assertThat(policy.isLate(now, departure)).isFalse();
    }
}
