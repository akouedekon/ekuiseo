package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.UnprocessableEntityException;
import bj.ekuiseo.api.domain.SearchAlert;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.UserPreferences;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.dto.alert.TripAlertRequest;
import bj.ekuiseo.api.dto.alert.TripAlertResponse;
import bj.ekuiseo.api.repository.SearchAlertRepository;
import bj.ekuiseo.api.repository.UserPreferencesRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constats F514/F523/F524/F525/F530 : liste et suppression, dedoublonnage, plafond, bornage, rayon, e-mail par defaut. */
class TripAlertServiceTest {

    private final SearchAlertRepository repository = mock(SearchAlertRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserPreferencesRepository preferencesRepository = mock(UserPreferencesRepository.class);
    private final TripAlertService service = new TripAlertService(repository, userRepository, preferencesRepository, 15.0);

    private final User user = User.builder().id(UUID.randomUUID()).firstName("Awa").build();
    private final TripAlertRequest request = new TripAlertRequest("Cotonou", 6.3703, 2.3912, "Bohicon", 7.1783, 2.0667,
            null, 2, TripType.INTERURBAIN, null);

    @BeforeEach
    void setUp() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(repository.findByUserIdAndActiveTrueOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());
        when(repository.save(any(SearchAlert.class))).thenAnswer(inv -> {
            SearchAlert a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        when(preferencesRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
    }

    @Test
    void create_undatedAlert_isBoundedToThirtyDays_withDefaultRadius_andEnablesEmail() {
        TripAlertResponse res = service.create(user.getId(), request);

        assertThat(res.date()).isNull();
        assertThat(res.activeUntil()).isEqualTo(LocalDate.now(Tz.BENIN).plusDays(30));
        assertThat(res.radiusKm()).isEqualTo(15.0);
        ArgumentCaptor<UserPreferences> prefs = ArgumentCaptor.forClass(UserPreferences.class);
        verify(preferencesRepository).save(prefs.capture());
        assertThat(prefs.getValue().isNotifyByEmail()).isTrue();
    }

    @Test
    void create_keepsTheRequestedRadius_andDoesNotTouchExistingPreferences() {
        when(preferencesRepository.findByUserId(user.getId()))
                .thenReturn(Optional.of(UserPreferences.builder().user(user).notifyByEmail(false).build()));

        TripAlertResponse res = service.create(user.getId(), new TripAlertRequest("Cotonou", 6.3703, 2.3912,
                "Bohicon", 7.1783, 2.0667, LocalDate.of(2026, 9, 12), 1, TripType.INTERURBAIN, 8.0));

        assertThat(res.radiusKm()).isEqualTo(8.0);
        assertThat(res.activeUntil()).isEqualTo(LocalDate.of(2026, 9, 12));
        verify(preferencesRepository, never()).save(any());
    }

    @Test
    void create_returnsTheExistingAlert_whenIdentical() {
        SearchAlert existing = SearchAlert.builder().id(UUID.randomUUID()).user(user)
                .originLabel("Cotonou").originLat(6.37031).originLng(2.39118) // memes coordonnees a 100 m pres
                .destLabel("Bohicon").destLat(7.1783).destLng(2.0667)
                .dateFrom(null).dateTo(LocalDate.now(Tz.BENIN).plusDays(10)).seats(2).tripType(TripType.INTERURBAIN)
                .radiusKm(15).active(true).build();
        when(repository.findByUserIdAndActiveTrueOrderByCreatedAtDesc(user.getId())).thenReturn(List.of(existing));

        TripAlertResponse res = service.create(user.getId(), request);

        assertThat(res.id()).isEqualTo(existing.getId());
        verify(repository, never()).save(any());
    }

    @Test
    void create_refusesBeyondTenActiveAlerts() {
        List<SearchAlert> active = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            active.add(SearchAlert.builder().id(UUID.randomUUID()).user(user).originLat(1 + i).originLng(1).destLat(2).destLng(2)
                    .seats(1).tripType(TripType.QUOTIDIEN).active(true).build());
        }
        when(repository.findByUserIdAndActiveTrueOrderByCreatedAtDesc(user.getId())).thenReturn(active);

        assertThatThrownBy(() -> service.create(user.getId(), request))
                .isInstanceOf(UnprocessableEntityException.class).hasMessageContaining("10 alertes");
        verify(repository, never()).save(any());
    }

    @Test
    void delete_isReservedToTheOwner() {
        SearchAlert alert = SearchAlert.builder().id(UUID.randomUUID()).user(user).build();
        when(repository.findById(alert.getId())).thenReturn(Optional.of(alert));

        assertThatThrownBy(() -> service.delete(UUID.randomUUID(), alert.getId())).isInstanceOf(ForbiddenException.class);
        verify(repository, never()).delete(any());

        service.delete(user.getId(), alert.getId());
        verify(repository).delete(alert);
    }

    @Test
    void list_returnsAllAlertsOfTheUser() {
        SearchAlert alert = SearchAlert.builder().id(UUID.randomUUID()).user(user).originLabel("A").destLabel("B")
                .seats(1).tripType(TripType.INTERURBAIN).radiusKm(15).active(false).build();
        when(repository.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of(alert));

        List<TripAlertResponse> rows = service.list(user.getId());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).active()).isFalse();
    }
}
