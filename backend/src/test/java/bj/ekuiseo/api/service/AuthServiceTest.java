package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.domain.OtpCode;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.dto.auth.OtpRegisterRequest;
import bj.ekuiseo.api.dto.auth.OtpRequestResponse;
import bj.ekuiseo.api.dto.auth.OtpVerifyRequest;
import bj.ekuiseo.api.mapper.UserMapper;
import bj.ekuiseo.api.repository.OtpCodeRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/** Constats F536 (compteur d essais OTP) et F023 (squat d un numero jamais verifie). */
class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final OtpCodeRepository otpCodeRepository = mock(OtpCodeRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final OtpDeliveryService otpDelivery = mock(OtpDeliveryService.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, otpCodeRepository, passwordEncoder, jwtService, otpDelivery, userMapper, 5);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(otpDelivery.resolveChannel(any())).thenReturn(OtpDeliveryService.Channel.EMAIL);
        when(otpDelivery.deliver(anyString(), any(), anyString())).thenReturn(new OtpRequestResponse("EMAIL", "ko***@example.com"));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void wrongCode_incrementsAttemptsAndPersistsThem() {
        OtpCode otp = OtpCode.builder().id(UUID.randomUUID()).phone("+2290197000322").codeHash("h")
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES)).attempts(3).build();
        when(otpCodeRepository.findFirstByPhoneAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(eq("+2290197000322"), any()))
                .thenReturn(Optional.of(otp));
        when(passwordEncoder.matches("000000", "h")).thenReturn(false);

        assertThatThrownBy(() -> service.verifyOtp(new OtpVerifyRequest("+2290197000322", "000000")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("incorrect");

        ArgumentCaptor<OtpCode> saved = ArgumentCaptor.forClass(OtpCode.class);
        verify(otpCodeRepository).save(saved.capture());
        assertThat(saved.getValue().getAttempts()).isEqualTo(4);
        assertThat(saved.getValue().getConsumedAt()).isNull();
    }

    @Test
    void verifyOtp_doesNotRollBackOnBadRequest() throws Exception {
        // La persistance de l increment n a de sens que si la transaction n est pas annulee
        // par l exception metier : la declaration doit rester (constat F536).
        Transactional tx = AuthService.class.getMethod("verifyOtp", OtpVerifyRequest.class).getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.noRollbackFor()).contains(BadRequestException.class);
    }

    @Test
    void burntCode_isConsumedAndRefused() {
        OtpCode otp = OtpCode.builder().id(UUID.randomUUID()).phone("+2290197000322").codeHash("h")
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES)).attempts(5).build();
        when(otpCodeRepository.findFirstByPhoneAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(eq("+2290197000322"), any()))
                .thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> service.verifyOtp(new OtpVerifyRequest("+2290197000322", "123456")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("maximal");
        assertThat(otp.getConsumedAt()).isNotNull();
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void registerWithOtp_reissuesForNeverVerifiedNumber() {
        User pending = User.builder().id(UUID.randomUUID()).phone("+2290196870371").email("ancien@example.com")
                .firstName("X").lastName("Y").passwordHash("x").build();
        when(userRepository.findByPhone("+2290196870371")).thenReturn(Optional.of(pending));
        when(userRepository.existsByEmailIgnoreCaseAndIdNot("koffi@example.com", pending.getId())).thenReturn(false);

        OtpRequestResponse res = service.registerWithOtp(
                new OtpRegisterRequest("+2290196870371", "Koffi", "Aholou", "koffi@example.com"));

        assertThat(res.channel()).isEqualTo("EMAIL");
        assertThat(pending.getFirstName()).isEqualTo("Koffi");
        assertThat(pending.getEmail()).isEqualTo("koffi@example.com");
        verify(userRepository).save(pending);
        verify(otpDelivery).deliver(eq("+2290196870371"), eq("koffi@example.com"), anyString());
    }

    @Test
    void registerWithOtp_refusesVerifiedNumber() {
        User verified = User.builder().id(UUID.randomUUID()).phone("+2290196870371").email("a@example.com")
                .emailVerified(true).passwordHash("x").build();
        when(userRepository.findByPhone("+2290196870371")).thenReturn(Optional.of(verified));

        assertThatThrownBy(() -> service.registerWithOtp(
                new OtpRegisterRequest("+2290196870371", "Koffi", "Aholou", "koffi@example.com")))
                .isInstanceOf(ConflictException.class);
        verify(otpDelivery, never()).deliver(anyString(), any(), anyString());
    }

    @Test
    void registerWithOtp_createsAccountAndSendsCode() {
        when(userRepository.findByPhone("+2290196870371")).thenReturn(Optional.empty());
        when(userRepository.existsByEmailIgnoreCase("koffi@example.com")).thenReturn(false);

        OtpRequestResponse res = service.registerWithOtp(
                new OtpRegisterRequest("+2290196870371", " Koffi ", "Aholou", "koffi@example.com"));

        assertThat(res.destination()).isEqualTo("ko***@example.com");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getFirstName()).isEqualTo("Koffi");
        assertThat(saved.getValue().isEmailVerified()).isFalse();
        verify(otpCodeRepository).save(any(OtpCode.class));
    }
}
