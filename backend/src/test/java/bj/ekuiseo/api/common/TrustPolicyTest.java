package bj.ekuiseo.api.common;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.TrustLevel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Contrat A.9 : UNVERIFIED, VERIFIED, EXPERIENCED (identite + 10 trajets + note >= 4,5 sur >= 5 avis). */
class TrustPolicyTest {

    @Test
    void levels() {
        assertThat(TrustPolicy.of(false, 50, new BigDecimal("5.00"), 40)).isEqualTo(TrustLevel.UNVERIFIED);
        assertThat(TrustPolicy.of(true, 9, new BigDecimal("5.00"), 40)).isEqualTo(TrustLevel.VERIFIED);
        assertThat(TrustPolicy.of(true, 10, new BigDecimal("4.49"), 40)).isEqualTo(TrustLevel.VERIFIED);
        assertThat(TrustPolicy.of(true, 10, new BigDecimal("4.50"), 4)).isEqualTo(TrustLevel.VERIFIED);
        assertThat(TrustPolicy.of(true, 10, new BigDecimal("4.50"), 5)).isEqualTo(TrustLevel.EXPERIENCED);
        assertThat(TrustPolicy.of(true, 10, null, 5)).isEqualTo(TrustLevel.VERIFIED);
        assertThat(TrustPolicy.of((User) null)).isEqualTo(TrustLevel.UNVERIFIED);
        assertThat(TrustPolicy.of(User.builder().identityVerified(true).tripsCompletedAsDriver(12)
                .ratingAvg(new BigDecimal("4.80")).ratingCount(7).build())).isEqualTo(TrustLevel.EXPERIENCED);
    }
}
