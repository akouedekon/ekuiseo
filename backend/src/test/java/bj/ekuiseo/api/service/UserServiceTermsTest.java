package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.mapper.UserMapper;
import bj.ekuiseo.api.mapper.VehicleMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverPayoutRepository;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.NotificationRepository;
import bj.ekuiseo.api.repository.PaymentAccountRepository;
import bj.ekuiseo.api.repository.SearchAlertRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserPreferencesRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.repository.VehicleRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constat F509 : acceptation horodatee de la version en vigueur des CGU, et signal termsAcceptanceRequired. */
class UserServiceTermsTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final TermsPolicy termsPolicy = new TermsPolicy("2026-09");
    private final UserService service = new UserService(userRepository, mock(VehicleRepository.class), mock(TripRepository.class),
            mock(BookingRepository.class), mock(MessageRepository.class), mock(UserPreferencesRepository.class),
            mock(PaymentAccountRepository.class), mock(SearchAlertRepository.class), mock(NotificationRepository.class),
            mock(IdentityVerificationRepository.class), mock(DriverPayoutRepository.class), mock(RefreshTokenService.class),
            auditService, mock(UserMapper.class), mock(VehicleMapper.class), termsPolicy,
            mock(bj.ekuiseo.api.repository.PushSubscriptionRepository.class), mock(IdentityDocumentService.class));

    private final User user = User.builder().id(UUID.randomUUID()).phone("+2290197000322").termsVersion("2025-01").build();

    @Test
    @SuppressWarnings("unchecked")
    void acceptTerms_recordsVersionAndDate_andAudits() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        assertThat(termsPolicy.acceptanceRequired(user)).isTrue();

        service.acceptTerms(user.getId(), " 2026-09 ");

        assertThat(user.getTermsVersion()).isEqualTo("2026-09");
        assertThat(user.getTermsAcceptedAt()).isNotNull();
        assertThat(termsPolicy.acceptanceRequired(user)).isFalse();
        verify(auditService).log(eq(user.getId()), eq("TERMS_ACCEPTED"), eq("user"), eq(user.getId()), any(Map.class));
    }

    @Test
    void acceptTerms_refusesAnotherVersion() {
        assertThatThrownBy(() -> service.acceptTerms(user.getId(), "2024-12"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("2026-09");
        verify(userRepository, never()).save(any());
    }

    @Test
    void termsPolicy_requiresAcceptanceForUnknownOrOutdatedVersions() {
        assertThat(termsPolicy.acceptanceRequired(User.builder().build())).isTrue();
        assertThat(termsPolicy.acceptanceRequired(User.builder().termsVersion("2026-09").build())).isFalse();
        assertThatThrownBy(() -> new TermsPolicy(" ")).isInstanceOf(IllegalStateException.class);
    }
}
