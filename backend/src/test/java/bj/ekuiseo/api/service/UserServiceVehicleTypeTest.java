package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.ComfortLevel;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.domain.enums.VehicleType;
import bj.ekuiseo.api.dto.user.VehicleRequest;
import bj.ekuiseo.api.mapper.UserMapper;
import bj.ekuiseo.api.mapper.VehicleMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserPreferencesRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.repository.VehicleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V22 : le type de vehicule borne les places (moto 1, tricycle 6, voiture 8) et vaut voiture par defaut. */
class UserServiceVehicleTypeTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final VehicleRepository vehicleRepository = mock(VehicleRepository.class);
    private final UserService service = new UserService(userRepository, vehicleRepository, mock(TripRepository.class),
            mock(BookingRepository.class), mock(MessageRepository.class), mock(UserPreferencesRepository.class),
            mock(bj.ekuiseo.api.repository.PaymentAccountRepository.class),
            mock(bj.ekuiseo.api.repository.SearchAlertRepository.class),
            mock(bj.ekuiseo.api.repository.NotificationRepository.class),
            mock(bj.ekuiseo.api.repository.IdentityVerificationRepository.class),
            mock(bj.ekuiseo.api.repository.DriverPayoutRepository.class),
            mock(RefreshTokenService.class), mock(AuditService.class), mock(UserMapper.class), mock(VehicleMapper.class),
            new TermsPolicy("2026-09"),
            mock(bj.ekuiseo.api.repository.PushSubscriptionRepository.class), mock(IdentityDocumentService.class));

    private final User owner = User.builder().id(UUID.randomUUID()).phone("+2290197000009").status(UserStatus.ACTIVE).build();

    private VehicleRequest request(VehicleType type, int seats) {
        return new VehicleRequest("Haojue", "HJ 125", "Jaune", "MT 4521 RB", seats, type, ComfortLevel.BASIC, null);
    }

    @Test
    void addVehicle_defaultsToCar_andKeepsTheDeclaredType() {
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(vehicleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.addVehicle(owner.getId(), request(null, 4));
        service.addVehicle(owner.getId(), request(VehicleType.TRICYCLE, 6));

        ArgumentCaptor<Vehicle> saved = ArgumentCaptor.forClass(Vehicle.class);
        verify(vehicleRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getVehicleType()).isEqualTo(VehicleType.CAR);
        assertThat(saved.getAllValues().get(1).getVehicleType()).isEqualTo(VehicleType.TRICYCLE);
    }

    @Test
    void addVehicle_refusesMoreSeatsThanTheTypeAllows() {
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.addVehicle(owner.getId(), request(VehicleType.MOTO, 2)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("un passager");
        assertThatThrownBy(() -> service.addVehicle(owner.getId(), request(VehicleType.TRICYCLE, 7)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("6");
        verify(vehicleRepository, never()).save(any());
    }
}
