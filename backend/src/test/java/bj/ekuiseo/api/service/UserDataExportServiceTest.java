package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.PaymentAccount;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripStop;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.IdentityDocumentType;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverPayoutRepository;
import bj.ekuiseo.api.repository.DriverSubscriptionRepository;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.NotificationRepository;
import bj.ekuiseo.api.repository.PaymentAccountRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.repository.ReportRepository;
import bj.ekuiseo.api.repository.ReviewRepository;
import bj.ekuiseo.api.repository.SearchAlertRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.TripStopRepository;
import bj.ekuiseo.api.repository.UserPreferencesRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constat F508 : export complet sans secret, un par 24 h, journalise. */
class UserDataExportServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PaymentAccountRepository paymentAccountRepository = mock(PaymentAccountRepository.class);
    private final IdentityVerificationRepository identityVerificationRepository = mock(IdentityVerificationRepository.class);
    private final TripRepository tripRepository = mock(TripRepository.class);
    private final TripStopRepository tripStopRepository = mock(TripStopRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final UserDataExportService service = new UserDataExportService(userRepository,
            mock(UserPreferencesRepository.class), mock(VehicleRepository.class), paymentAccountRepository,
            identityVerificationRepository, mock(DriverSubscriptionRepository.class), tripRepository, tripStopRepository,
            mock(BookingRepository.class), mock(PaymentRepository.class), mock(DriverPayoutRepository.class),
            mock(ReviewRepository.class), mock(MessageRepository.class), mock(NotificationRepository.class),
            mock(SearchAlertRepository.class), mock(ReportRepository.class), auditService,
            mock(bj.ekuiseo.api.repository.PushSubscriptionRepository.class),
            mock(bj.ekuiseo.api.repository.IdentityDocumentRepository.class), 24);

    private final User user = User.builder().id(UUID.randomUUID()).phone("+2290197000322").email("awa@example.bj")
            .firstName("Awa").lastName("K").passwordHash("secret-hash").termsVersion("2026-09").build();

    @BeforeEach
    void setUp() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void export_buildsEverySection_withoutSecrets_andStampsTheUser() {
        when(paymentAccountRepository.findByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(
                PaymentAccount.builder().id(UUID.randomUUID()).user(user).provider(MobileMoneyOperator.MTN_MOMO)
                        .phone("+2290197000322").isDefault(true).build()));
        when(identityVerificationRepository.findByUserId(user.getId())).thenReturn(Optional.of(
                IdentityVerification.builder().user(user).status(IdentityVerificationStatus.APPROVED)
                        .documentType(IdentityDocumentType.CNI).documentNumber("B1234567").build()));
        Trip trip = Trip.builder().id(UUID.randomUUID()).driver(user).tripType(TripType.INTERURBAIN).originLabel("Cotonou")
                .destLabel("Parakou").departureAt(Instant.now()).status(TripStatus.PUBLISHED).seatsTotal(4).seatsAvailable(4)
                .pricePerSeat(6000).build();
        when(tripRepository.findByDriverIdOrderByDepartureAtDesc(user.getId())).thenReturn(List.of(trip));
        when(tripStopRepository.findByTripIdInOrderByPositionAsc(anyCollection())).thenReturn(List.of(
                TripStop.builder().id(UUID.randomUUID()).trip(trip).position(1).label("Bohicon").lat(7.17).lng(2.06).priceFromOrigin(2500).build()));

        Map<String, Object> doc = service.export(user.getId());

        assertThat(doc).containsKeys("user", "preferences", "vehicles", "paymentAccounts", "identityVerification", "subscriptions",
                "trips", "bookings", "payments", "payouts", "reviewsWritten", "reviewsReceived", "messagesSent", "notifications",
                "searchAlerts", "reportsFiled");
        Map<String, Object> u = (Map<String, Object>) doc.get("user");
        assertThat(u).containsEntry("phone", "+2290197000322").containsEntry("termsVersion", "2026-09").doesNotContainKey("passwordHash");
        assertThat(doc.toString()).doesNotContain("secret-hash").doesNotContain("B1234567");
        Map<String, Object> account = ((List<Map<String, Object>>) doc.get("paymentAccounts")).get(0);
        assertThat((String) account.get("phone")).endsWith("22").startsWith("*");
        Map<String, Object> identity = (Map<String, Object>) doc.get("identityVerification");
        assertThat(identity).containsEntry("status", "APPROVED").containsEntry("documentType", "CNI").doesNotContainKey("documentNumber");
        List<Map<String, Object>> trips = (List<Map<String, Object>>) doc.get("trips");
        assertThat(trips).hasSize(1);
        assertThat((List<?>) trips.get(0).get("stops")).hasSize(1);

        assertThat(user.getLastExportAt()).isNotNull();
        verify(auditService).log(eq(user.getId()), eq("USER_DATA_EXPORTED"), eq("user"), eq(user.getId()), any());
    }

    @Test
    void export_isLimitedToOnePerDay() {
        user.setLastExportAt(Instant.now().minus(2, ChronoUnit.HOURS));

        assertThatThrownBy(() -> service.export(user.getId())).isInstanceOf(TooManyRequestsException.class);
        verify(auditService, never()).log(any(), any(), any(), any(), any());

        user.setLastExportAt(Instant.now().minus(25, ChronoUnit.HOURS));
        assertThat(service.export(user.getId())).isNotEmpty();
    }
}
