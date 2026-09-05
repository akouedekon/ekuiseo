package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.UserPreferences;
import bj.ekuiseo.api.domain.enums.ChattyLevel;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.user.PublicUserProfileResponse;
import bj.ekuiseo.api.mapper.UserMapper;
import bj.ekuiseo.api.mapper.VehicleMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserPreferencesRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.repository.VehicleRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifie UserService#getPublicProfile (regle metier n.22) : reliabilityRate et
 * responseTimeMinutes doivent passer a null sous le seuil de 5 echantillons
 * mesurables (plutot que d'afficher un "0 %" ou un delai trompeur), etre
 * correctement arrondis au-dessus, et les preferences publiques doivent
 * retomber sur les valeurs par defaut de UserPreferences en l'absence de ligne.
 */
class UserServicePublicProfileTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final VehicleRepository vehicleRepository = mock(VehicleRepository.class);
    private final TripRepository tripRepository = mock(TripRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final UserPreferencesRepository userPreferencesRepository = mock(UserPreferencesRepository.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final VehicleMapper vehicleMapper = mock(VehicleMapper.class);

    private final UserService service = new UserService(userRepository, vehicleRepository, tripRepository,
            bookingRepository, messageRepository, userPreferencesRepository,
            mock(bj.ekuiseo.api.repository.PaymentAccountRepository.class),
            mock(bj.ekuiseo.api.repository.SearchAlertRepository.class),
            mock(bj.ekuiseo.api.repository.NotificationRepository.class),
            mock(bj.ekuiseo.api.repository.IdentityVerificationRepository.class),
            mock(bj.ekuiseo.api.repository.DriverPayoutRepository.class),
            mock(RefreshTokenService.class), mock(AuditService.class), userMapper, vehicleMapper);

    private static BookingRepository.DriverReliabilityStats reliability(long completed, long noShow, long lateCancelled) {
        return new BookingRepository.DriverReliabilityStats() {
            public long getCompleted() {
                return completed;
            }

            public long getNoShow() {
                return noShow;
            }

            public long getLateCancelledByDriver() {
                return lateCancelled;
            }
        };
    }

    private static MessageRepository.DriverResponseTimeStats responseTime(Double medianMinutes, long sampleSize) {
        return new MessageRepository.DriverResponseTimeStats() {
            public Double getMedianMinutes() {
                return medianMinutes;
            }

            public long getSampleSize() {
                return sampleSize;
            }
        };
    }

    private void stubCommon(UUID driverId) {
        User driver = User.builder().id(driverId).firstName("Awa").lastName("K.")
                .ratingAvg(BigDecimal.valueOf(4.5)).ratingCount(10).createdAt(Instant.now()).build();
        when(userRepository.findById(driverId)).thenReturn(Optional.of(driver));
        when(vehicleRepository.findByOwnerId(driverId)).thenReturn(List.of());
        when(tripRepository.countByDriverIdAndStatus(driverId, TripStatus.COMPLETED)).thenReturn(3L);
    }

    @Test
    void reliabilityRate_isNull_belowFiveMeasurableTrips() {
        UUID driverId = UUID.randomUUID();
        stubCommon(driverId);
        when(bookingRepository.getReliabilityStats(driverId)).thenReturn(reliability(3, 0, 1)); // total = 4
        when(messageRepository.getResponseTimeStats(eq(driverId), any())).thenReturn(responseTime(null, 0));
        when(userPreferencesRepository.findByUserId(driverId)).thenReturn(Optional.empty());

        PublicUserProfileResponse profile = service.getPublicProfile(driverId);

        assertThat(profile.reliabilityRate()).isNull();
    }

    @Test
    void reliabilityRate_isComputedAndRounded_atOrAboveFiveMeasurableTrips() {
        UUID driverId = UUID.randomUUID();
        stubCommon(driverId);
        // 9 honores sur 10 (1 NO_SHOW) -> 90%
        when(bookingRepository.getReliabilityStats(driverId)).thenReturn(reliability(9, 1, 0));
        when(messageRepository.getResponseTimeStats(eq(driverId), any())).thenReturn(responseTime(null, 0));
        when(userPreferencesRepository.findByUserId(driverId)).thenReturn(Optional.empty());

        PublicUserProfileResponse profile = service.getPublicProfile(driverId);

        assertThat(profile.reliabilityRate()).isEqualTo(90);
    }

    @Test
    void responseTimeMinutes_isNull_belowFiveMeasurableExchanges() {
        UUID driverId = UUID.randomUUID();
        stubCommon(driverId);
        when(bookingRepository.getReliabilityStats(driverId)).thenReturn(reliability(0, 0, 0));
        when(messageRepository.getResponseTimeStats(eq(driverId), any())).thenReturn(responseTime(12.0, 4)); // 4 < 5
        when(userPreferencesRepository.findByUserId(driverId)).thenReturn(Optional.empty());

        PublicUserProfileResponse profile = service.getPublicProfile(driverId);

        assertThat(profile.responseTimeMinutes()).isNull();
    }

    @Test
    void responseTimeMinutes_isRounded_atOrAboveFiveMeasurableExchanges() {
        UUID driverId = UUID.randomUUID();
        stubCommon(driverId);
        when(bookingRepository.getReliabilityStats(driverId)).thenReturn(reliability(0, 0, 0));
        when(messageRepository.getResponseTimeStats(eq(driverId), any())).thenReturn(responseTime(7.6, 5));
        when(userPreferencesRepository.findByUserId(driverId)).thenReturn(Optional.empty());

        PublicUserProfileResponse profile = service.getPublicProfile(driverId);

        assertThat(profile.responseTimeMinutes()).isEqualTo(8); // arrondi de 7.6
    }

    @Test
    void preferences_fallBackToDefaults_whenNoRowStored() {
        UUID driverId = UUID.randomUUID();
        stubCommon(driverId);
        when(bookingRepository.getReliabilityStats(driverId)).thenReturn(reliability(0, 0, 0));
        when(messageRepository.getResponseTimeStats(eq(driverId), any())).thenReturn(responseTime(null, 0));
        when(userPreferencesRepository.findByUserId(driverId)).thenReturn(Optional.empty());

        PublicUserProfileResponse profile = service.getPublicProfile(driverId);

        assertThat(profile.preferences().smoking()).isFalse();
        assertThat(profile.preferences().music()).isTrue();
        assertThat(profile.preferences().pets()).isFalse();
        assertThat(profile.preferences().chatty()).isEqualTo(ChattyLevel.DEPENDS);
    }

    @Test
    void preferences_reflectStoredRow_whenPresent() {
        UUID driverId = UUID.randomUUID();
        stubCommon(driverId);
        when(bookingRepository.getReliabilityStats(driverId)).thenReturn(reliability(0, 0, 0));
        when(messageRepository.getResponseTimeStats(eq(driverId), any())).thenReturn(responseTime(null, 0));
        UserPreferences stored = UserPreferences.builder().smoking(true).music(false).pets(true)
                .chatty(ChattyLevel.TALKATIVE).build();
        when(userPreferencesRepository.findByUserId(driverId)).thenReturn(Optional.of(stored));

        PublicUserProfileResponse profile = service.getPublicProfile(driverId);

        assertThat(profile.preferences().smoking()).isTrue();
        assertThat(profile.preferences().music()).isFalse();
        assertThat(profile.preferences().pets()).isTrue();
        assertThat(profile.preferences().chatty()).isEqualTo(ChattyLevel.TALKATIVE);
    }
}
