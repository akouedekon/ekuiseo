package bj.ekuiseo.api.service.admin;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.dto.admin.AdminUserResponse;
import bj.ekuiseo.api.mapper.BookingMapper;
import bj.ekuiseo.api.mapper.TripMapper;
import bj.ekuiseo.api.mapper.VehicleMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.repository.PaymentAccountRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.repository.VehicleRepository;
import bj.ekuiseo.api.service.AuditService;
import bj.ekuiseo.api.service.BookingService;
import bj.ekuiseo.api.service.NotificationService;
import bj.ekuiseo.api.service.RefreshTokenService;
import bj.ekuiseo.api.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constat F237 : la recherche d utilisateurs est paginee cote serveur, taille bornee, consultation journalisee (F520). */
class AdminUserServiceSearchTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final TripRepository tripRepository = mock(TripRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final AdminUserService service = new AdminUserService(userRepository, mock(VehicleRepository.class),
            tripRepository, bookingRepository, auditService, mock(RefreshTokenService.class),
            mock(BookingService.class), mock(IdentityVerificationRepository.class), mock(NotificationService.class),
            mock(UserService.class), mock(PaymentAccountRepository.class), mock(PaymentRepository.class),
            mock(VehicleMapper.class), mock(BookingMapper.class), mock(TripMapper.class));

    @Test
    @SuppressWarnings("unchecked")
    void search_returnsTheRequestedPage_clampsTheSize_andAuditsTheLookup() {
        UUID adminId = UUID.randomUUID();
        User user = User.builder().id(UUID.randomUUID()).firstName("Awa").lastName("Test").phone("+2290197000321")
                .role(Role.USER).status(UserStatus.ACTIVE).build();
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(userRepository.search(eq("awa"), pageable.capture()))
                .thenAnswer(inv -> new PageImpl<>(List.of(user), PageRequest.of(3, 100), 301));
        when(tripRepository.countByDriverIds(any())).thenReturn(List.of());
        when(bookingRepository.countByPassengerIds(any())).thenReturn(List.of());

        Page<AdminUserResponse> page = service.search(adminId, "  awa ", 3, 5_000);

        assertThat(pageable.getValue().getPageNumber()).isEqualTo(3);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        assertThat(page.getTotalElements()).isEqualTo(301);
        assertThat(page.getNumber()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).id()).isEqualTo(user.getId());
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq(adminId), eq("ADMIN_USERS_SEARCHED"), eq("user"), any(), details.capture());
        assertThat(details.getValue()).containsEntry("q", "awa").containsEntry("page", 3)
                .containsEntry("resultCount", 1).containsEntry("totalCount", 301L);
    }

    @Test
    void search_withNoResult_returnsAnEmptyPage_withoutCountingAnything() {
        when(userRepository.search(eq(""), any())).thenReturn(Page.empty());

        Page<AdminUserResponse> page = service.search(UUID.randomUUID(), null, -4, 0);

        assertThat(page.getContent()).isEmpty();
        verify(tripRepository, org.mockito.Mockito.never()).countByDriverIds(any());
    }
}
