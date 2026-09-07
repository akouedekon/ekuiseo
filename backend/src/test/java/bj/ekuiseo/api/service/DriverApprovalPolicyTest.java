package bj.ekuiseo.api.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Delai de reponse du conducteur (V19) : 24 h, plafonne a 2 h avant le depart, jamais dans le passe. */
class DriverApprovalPolicyTest {

    private final DriverApprovalPolicy policy = new DriverApprovalPolicy(24);
    private final Instant now = Instant.parse("2026-09-10T08:00:00Z");

    @Test
    void deadline_isTwentyFourHours_whenTheDepartureIsFarAway() {
        Instant departure = now.plus(5, ChronoUnit.DAYS);
        assertThat(policy.deadline(now, departure)).isEqualTo(now.plus(24, ChronoUnit.HOURS));
    }

    @Test
    void deadline_isCappedTwoHoursBeforeDeparture() {
        Instant departure = now.plus(10, ChronoUnit.HOURS);
        assertThat(policy.deadline(now, departure)).isEqualTo(departure.minus(2, ChronoUnit.HOURS));
    }

    @Test
    void deadline_fallsBackToTheDeparture_whenTheRequestIsMadeInTheLastTwoHours() {
        Instant departure = now.plus(90, ChronoUnit.MINUTES);
        assertThat(policy.deadline(now, departure)).isEqualTo(departure);
    }

    @Test
    void approvalHours_isAtLeastOne() {
        assertThat(new DriverApprovalPolicy(0).approvalHours()).isEqualTo(1);
        assertThat(policy.approvalHours()).isEqualTo(24);
    }
}
