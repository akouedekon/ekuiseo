package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.UnauthorizedException;
import bj.ekuiseo.api.domain.OtpCode;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.dto.auth.AuthResponse;
import bj.ekuiseo.api.dto.auth.OtpRegisterRequest;
import bj.ekuiseo.api.dto.auth.OtpRequestRequest;
import bj.ekuiseo.api.dto.auth.OtpRequestResponse;
import bj.ekuiseo.api.dto.auth.OtpVerifyRequest;
import bj.ekuiseo.api.dto.auth.RefreshRequest;
import bj.ekuiseo.api.mapper.UserMapper;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constats F023 (compte en attente, squat), F410 (normalisation), F001 (refresh), F536 via OtpCodeService. */
class AuthServiceTest {

    private static final String SECRET = "test-secret-key-for-jwt-unit-tests-0123456789abcdef";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final OtpCodeService otpCodes = mock(OtpCodeService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtService jwtService = new JwtService(SECRET, 60, 30);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final OtpDeliveryService otpDelivery = mock(OtpDeliveryService.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, otpCodes, passwordEncoder, jwtService, refreshTokens, otpDelivery, userMapper);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(otpDelivery.resolveChannel(any())).thenReturn(OtpDeliveryService.Channel.EMAIL);
        when(otpDelivery.deliver(anyString(), any(), anyString())).thenReturn(new OtpRequestResponse("EMAIL", "ko***@example.com"));
        when(otpCodes.issue(anyString(), anyString(), anyString())).thenReturn("123456");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(refreshTokens.issue(any())).thenReturn("refresh-token");
    }

    @Test
    void registerWithOtp_createsPendingAccountWithNormalizedPhone() {
        when(userRepository.findByPhone("+2290196870371")).thenReturn(Optional.empty());
        when(userRepository.existsByEmailIgnoreCase("koffi@example.com")).thenReturn(false);

        OtpRequestResponse res = service.registerWithOtp(
                new OtpRegisterRequest("+229 01 96 87 03 71", " Koffi ", "Aholou", "koffi@example.com"));

        assertThat(res.destination()).isEqualTo("ko***@example.com");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPhone()).isEqualTo("+2290196870371");
        assertThat(saved.getValue().getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
        assertThat(saved.getValue().getFirstName()).isEqualTo("Koffi");
        verify(otpCodes).issue("+2290196870371", "LOGIN", "EMAIL");
        verify(otpDelivery).deliver("+2290196870371", "koffi@example.com", "123456");
    }

    @Test
    void registerWithOtp_reissuesForPendingNumber_andRefusesVerifiedOne() {
        User pending = User.builder().id(UUID.randomUUID()).phone("+2290196870371").email("ancien@example.com")
                .firstName("X").lastName("Y").passwordHash("x").status(UserStatus.PENDING_VERIFICATION).build();
        when(userRepository.findByPhone("+2290196870371")).thenReturn(Optional.of(pending));
        when(userRepository.existsByEmailIgnoreCaseAndIdNot("koffi@example.com", pending.getId())).thenReturn(false);

        service.registerWithOtp(new OtpRegisterRequest("0196870371", "Koffi", "Aholou", "koffi@example.com"));
        assertThat(pending.getEmail()).isEqualTo("koffi@example.com");
        assertThat(pending.getFirstName()).isEqualTo("Koffi");

        User verified = User.builder().id(UUID.randomUUID()).phone("+2290197000322").email("a@example.com")
                .emailVerified(true).passwordHash("x").status(UserStatus.ACTIVE).build();
        when(userRepository.findByPhone("+2290197000322")).thenReturn(Optional.of(verified));
        assertThatThrownBy(() -> service.registerWithOtp(
                new OtpRegisterRequest("+2290197000322", "Koffi", "Aholou", "autre@example.com")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void registerWithOtp_rejectsLegacyEightDigitNumbers() {
        assertThatThrownBy(() -> service.registerWithOtp(
                new OtpRegisterRequest("+22997000322", "Koffi", "Aholou", "koffi@example.com")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("10 chiffres");
        verify(userRepository, never()).save(any());
    }

    @Test
    void requestOtp_refusesSuspendedAccount_andNormalizesPhone() {
        User suspended = User.builder().id(UUID.randomUUID()).phone("+2290197000322").email("a@example.com")
                .passwordHash("x").status(UserStatus.SUSPENDED).build();
        when(userRepository.findByPhone("+2290197000322")).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.requestOtp(new OtpRequestRequest("01 97 00 03 22")))
                .isInstanceOf(UnauthorizedException.class);
        verify(otpDelivery, never()).deliver(anyString(), any(), anyString());
    }

    @Test
    void verifyOtp_activatesPendingAccount_andIssuesRegisteredRefreshToken() {
        User pending = User.builder().id(UUID.randomUUID()).phone("+2290196870371").email("koffi@example.com")
                .passwordHash("x").status(UserStatus.PENDING_VERIFICATION).build();
        when(otpCodes.consume("+2290196870371", "LOGIN", "123456"))
                .thenReturn(OtpCode.builder().phone("+2290196870371").channel("EMAIL").build());
        when(userRepository.findByPhone("+2290196870371")).thenReturn(Optional.of(pending));

        AuthResponse res = service.verifyOtp(new OtpVerifyRequest("+229 01 96 87 03 71", "123456"));

        assertThat(pending.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(pending.isEmailVerified()).isTrue();
        assertThat(res.refreshToken()).isEqualTo("refresh-token");
        assertThat(jwtService.extractUserIdFromAccessToken(res.accessToken())).isEqualTo(pending.getId());
        verify(refreshTokens).issue(pending.getId());
    }

    @Test
    void refresh_rotatesAndRefusesSuspendedAccounts() {
        UUID id = UUID.randomUUID();
        when(refreshTokens.rotate("old")).thenReturn(new RefreshTokenService.Rotation(id, "new"));
        when(userRepository.findById(id)).thenReturn(Optional.of(
                User.builder().id(id).phone("+2290197000322").passwordHash("x").status(UserStatus.ACTIVE).build()));

        AuthResponse res = service.refresh(new RefreshRequest("old"));
        assertThat(res.refreshToken()).isEqualTo("new");
        assertThat(jwtService.extractUserIdFromAccessToken(res.accessToken())).isEqualTo(id);

        UUID suspendedId = UUID.randomUUID();
        when(refreshTokens.rotate("old2")).thenReturn(new RefreshTokenService.Rotation(suspendedId, "new2"));
        when(userRepository.findById(suspendedId)).thenReturn(Optional.of(
                User.builder().id(suspendedId).phone("+2290197000323").passwordHash("x").status(UserStatus.SUSPENDED).build()));
        assertThatThrownBy(() -> service.refresh(new RefreshRequest("old2"))).isInstanceOf(UnauthorizedException.class);
        verify(refreshTokens).revokeAll(suspendedId);
    }

    @Test
    void logout_revokesOnlyWhenATokenIsGiven() {
        service.logout(null);
        service.logout("  ");
        verify(refreshTokens, never()).revoke(anyString());
        service.logout("token");
        verify(refreshTokens).revoke("token");
    }
}
