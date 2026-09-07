package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.UnauthorizedException;
import bj.ekuiseo.api.domain.OtpCode;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.dto.auth.AuthResponse;
import bj.ekuiseo.api.dto.auth.OtpChannel;
import bj.ekuiseo.api.dto.auth.OtpRegisterRequest;
import bj.ekuiseo.api.dto.auth.OtpRequestRequest;
import bj.ekuiseo.api.dto.auth.OtpRequestResponse;
import bj.ekuiseo.api.dto.auth.OtpVerifyRequest;
import bj.ekuiseo.api.dto.auth.RefreshRequest;
import bj.ekuiseo.api.mapper.UserMapper;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.security.JwtService;
import bj.ekuiseo.api.security.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
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

/**
 * Constats F023 (compte en attente, squat), F410 (normalisation), F001 (refresh), F536 via
 * OtpCodeService. Phase 3 (F541/F544/F148) : journal d audit des evenements d authentification
 * avec IP et User-Agent, derniere connexion, plus de hachage factice.
 */
class AuthServiceTest {

    private static final String SECRET = "test-secret-key-for-jwt-unit-tests-0123456789abcdef";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final OtpCodeService otpCodes = mock(OtpCodeService.class);
    private final JwtService jwtService = new JwtService(SECRET, 60, 30);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final OtpDeliveryService otpDelivery = mock(OtpDeliveryService.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final TermsPolicy termsPolicy = new TermsPolicy("2026-09");
    private final AuditService auditService = mock(AuditService.class);
    private final RequestContext requestContext = mock(RequestContext.class);
    private AuthService service;

    private static OtpRegisterRequest register(String phone, String firstName, String lastName, String email) {
        return new OtpRegisterRequest(phone, firstName, lastName, email, true, "2026-09");
    }

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, otpCodes, jwtService, refreshTokens, otpDelivery,
                userMapper, termsPolicy, auditService, requestContext);
        when(otpDelivery.resolveChannel(any())).thenReturn(OtpDeliveryService.Channel.EMAIL);
        when(otpDelivery.deliver(anyString(), any(), anyString())).thenReturn(new OtpRequestResponse(OtpChannel.EMAIL, "ko***@example.com"));
        when(otpCodes.issue(anyString(), anyString(), anyString())).thenReturn("123456");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(refreshTokens.issue(any())).thenReturn("refresh-token");
        when(requestContext.clientIp()).thenReturn("41.85.10.9");
        when(requestContext.userAgent()).thenReturn("Mozilla/5.0 (Android)");
    }

    @Test
    void registerWithOtp_createsPendingAccountWithNormalizedPhone_withoutPasswordHash_andAudits() {
        when(userRepository.findByPhone("+2290196870371")).thenReturn(Optional.empty());
        when(userRepository.existsByEmailIgnoreCase("koffi@example.com")).thenReturn(false);

        OtpRequestResponse res = service.registerWithOtp(
                register("+229 01 96 87 03 71", " Koffi ", "Aholou", "koffi@example.com"));

        assertThat(res.destination()).isEqualTo("ko***@example.com");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPhone()).isEqualTo("+2290196870371");
        assertThat(saved.getValue().getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
        assertThat(saved.getValue().getFirstName()).isEqualTo("Koffi");
        // Constat F148 : plus de hachage factice, la colonne est nulle depuis V17.
        assertThat(saved.getValue().getPasswordHash()).isNull();
        // Constat F509 : acceptation des CGU horodatee avec la version en vigueur.
        assertThat(saved.getValue().getTermsVersion()).isEqualTo("2026-09");
        assertThat(saved.getValue().getTermsAcceptedAt()).isNotNull();
        verify(otpCodes).issue("+2290196870371", "LOGIN", "EMAIL");
        verify(otpDelivery).deliver("+2290196870371", "koffi@example.com", "123456");
        // Constat F541 : OTP_REQUESTED avec canal, destination masquee, IP et User-Agent.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(any(), eq(AuthService.AUDIT_OTP_REQUESTED), eq("user"), any(), details.capture());
        assertThat(details.getValue()).containsEntry("channel", "EMAIL").containsEntry("destination", "ko***@example.com")
                .containsEntry("ip", "41.85.10.9").containsEntry("userAgent", "Mozilla/5.0 (Android)");
    }

    @Test
    void registerWithOtp_reissuesForPendingNumber_andRefusesVerifiedOne_withASingleMessage() {
        User pending = User.builder().id(UUID.randomUUID()).phone("+2290196870371").email("ancien@example.com")
                .firstName("X").lastName("Y").status(UserStatus.PENDING_VERIFICATION).build();
        when(userRepository.findByPhone("+2290196870371")).thenReturn(Optional.of(pending));
        when(userRepository.existsByEmailIgnoreCaseAndIdNot("koffi@example.com", pending.getId())).thenReturn(false);

        service.registerWithOtp(register("0196870371", "Koffi", "Aholou", "koffi@example.com"));
        assertThat(pending.getEmail()).isEqualTo("koffi@example.com");
        assertThat(pending.getFirstName()).isEqualTo("Koffi");
        assertThat(pending.getTermsVersion()).isEqualTo("2026-09");

        User verified = User.builder().id(UUID.randomUUID()).phone("+2290197000322").email("a@example.com")
                .emailVerified(true).status(UserStatus.ACTIVE).build();
        when(userRepository.findByPhone("+2290197000322")).thenReturn(Optional.of(verified));
        assertThatThrownBy(() -> service.registerWithOtp(register("+2290197000322", "Koffi", "Aholou", "autre@example.com")))
                .isInstanceOf(ConflictException.class).hasMessage(AuthService.ALREADY_USED);

        // Un e-mail deja pris produit exactement le meme message (constat F512 : pas d enumeration).
        when(userRepository.findByPhone("+2290197000323")).thenReturn(Optional.empty());
        when(userRepository.existsByEmailIgnoreCase("a@example.com")).thenReturn(true);
        assertThatThrownBy(() -> service.registerWithOtp(register("+2290197000323", "Koffi", "Aholou", "a@example.com")))
                .isInstanceOf(ConflictException.class).hasMessage(AuthService.ALREADY_USED);
    }

    @Test
    void registerWithOtp_requiresCurrentTermsVersion_andAcceptance() {
        when(userRepository.findByPhone("+2290196870371")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerWithOtp(
                new OtpRegisterRequest("+2290196870371", "Koffi", "Aholou", "koffi@example.com", true, "2025-01")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("2026-09");
        assertThatThrownBy(() -> service.registerWithOtp(
                new OtpRegisterRequest("+2290196870371", "Koffi", "Aholou", "koffi@example.com", false, "2026-09")))
                .isInstanceOf(BadRequestException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerWithOtp_rejectsLegacyEightDigitNumbers() {
        assertThatThrownBy(() -> service.registerWithOtp(register("+22997000322", "Koffi", "Aholou", "koffi@example.com")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("10 chiffres");
        verify(userRepository, never()).save(any());
    }

    @Test
    void requestOtp_refusesSuspendedAccount_andNormalizesPhone() {
        User suspended = User.builder().id(UUID.randomUUID()).phone("+2290197000322").email("a@example.com")
                .status(UserStatus.SUSPENDED).build();
        when(userRepository.findByPhone("+2290197000322")).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.requestOtp(new OtpRequestRequest("01 97 00 03 22")))
                .isInstanceOf(UnauthorizedException.class);
        verify(otpDelivery, never()).deliver(anyString(), any(), anyString());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void verifyOtp_activatesPendingAccount_datesTheLogin_issuesRefreshToken_andAudits() {
        User pending = User.builder().id(UUID.randomUUID()).phone("+2290196870371").email("koffi@example.com")
                .status(UserStatus.PENDING_VERIFICATION).build();
        when(otpCodes.consume("+2290196870371", "LOGIN", "123456"))
                .thenReturn(OtpCode.builder().phone("+2290196870371").channel("EMAIL").build());
        when(userRepository.findByPhone("+2290196870371")).thenReturn(Optional.of(pending));

        AuthResponse res = service.verifyOtp(new OtpVerifyRequest("+229 01 96 87 03 71", "123456"));

        assertThat(pending.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(pending.isEmailVerified()).isTrue();
        // Constat F544 : derniere connexion datee sur le compte (V17).
        assertThat(pending.getLastLoginAt()).isNotNull();
        assertThat(res.refreshToken()).isEqualTo("refresh-token");
        assertThat(jwtService.extractUserIdFromAccessToken(res.accessToken())).isEqualTo(pending.getId());
        verify(refreshTokens).issue(pending.getId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq(pending.getId()), eq(AuthService.AUDIT_OTP_VERIFY_SUCCEEDED), eq("user"),
                eq(pending.getId()), details.capture());
        assertThat(details.getValue()).containsEntry("ip", "41.85.10.9").containsEntry("channel", "EMAIL");
    }

    /** Constat F541 : un code faux est journalise (OTP_VERIFY_FAILED) avant d etre signale en 400. */
    @Test
    void verifyOtp_wrongCode_isAudited_thenRejected() {
        User user = User.builder().id(UUID.randomUUID()).phone("+2290196870371").email("koffi@example.com")
                .status(UserStatus.ACTIVE).build();
        when(userRepository.findByPhone("+2290196870371")).thenReturn(Optional.of(user));
        when(otpCodes.consume("+2290196870371", "LOGIN", "000000")).thenThrow(new BadRequestException("Code incorrect"));

        assertThatThrownBy(() -> service.verifyOtp(new OtpVerifyRequest("+2290196870371", "000000")))
                .isInstanceOf(BadRequestException.class).hasMessage("Code incorrect");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq(user.getId()), eq(AuthService.AUDIT_OTP_VERIFY_FAILED), eq("user"), eq(user.getId()),
                details.capture());
        assertThat(details.getValue()).containsEntry("reason", "Code incorrect").containsEntry("phone", "************71")
                .containsEntry("userAgent", "Mozilla/5.0 (Android)");
        assertThat(user.getLastLoginAt()).isNull();
        verify(refreshTokens, never()).issue(any());
    }

    /** Hors requete HTTP (tache, test), l audit reste possible avec des champs vides. */
    @Test
    void auditDetails_areEmptyOutsideOfAnHttpRequest() {
        when(requestContext.clientIp()).thenReturn(null);
        when(requestContext.userAgent()).thenReturn(null);
        User user = User.builder().id(UUID.randomUUID()).phone("+2290196870371").email("koffi@example.com")
                .status(UserStatus.ACTIVE).build();
        when(userRepository.findByPhone("+2290196870371")).thenReturn(Optional.of(user));

        service.requestOtp(new OtpRequestRequest("+2290196870371"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq(user.getId()), eq(AuthService.AUDIT_OTP_REQUESTED), eq("user"), eq(user.getId()), details.capture());
        assertThat(details.getValue()).containsEntry("ip", "").containsEntry("userAgent", "");
    }

    @Test
    void refresh_rotatesAndAudits_andRefusesSuspendedAccounts() {
        UUID id = UUID.randomUUID();
        when(refreshTokens.rotate("old")).thenReturn(new RefreshTokenService.Rotation(id, "new"));
        when(userRepository.findById(id)).thenReturn(Optional.of(
                User.builder().id(id).phone("+2290197000322").status(UserStatus.ACTIVE).build()));

        AuthResponse res = service.refresh(new RefreshRequest("old"));
        assertThat(res.refreshToken()).isEqualTo("new");
        assertThat(jwtService.extractUserIdFromAccessToken(res.accessToken())).isEqualTo(id);
        verify(auditService).log(eq(id), eq(AuthService.AUDIT_TOKEN_REFRESHED), eq("user"), eq(id), any());

        UUID suspendedId = UUID.randomUUID();
        when(refreshTokens.rotate("old2")).thenReturn(new RefreshTokenService.Rotation(suspendedId, "new2"));
        when(userRepository.findById(suspendedId)).thenReturn(Optional.of(
                User.builder().id(suspendedId).phone("+2290197000323").status(UserStatus.SUSPENDED).build()));
        assertThatThrownBy(() -> service.refresh(new RefreshRequest("old2"))).isInstanceOf(UnauthorizedException.class);
        verify(refreshTokens).revokeAll(suspendedId);
        verify(auditService, never()).log(eq(suspendedId), any(), any(), any(), any());
    }

    /**
     * Constats F355/F405 : le service remet la paire complete (le controleur deplace le refresh
     * token dans le cookie HttpOnly) ; la forme exposee au client n en garde que l acces et le profil.
     */
    @Test
    void refresh_andVerify_returnFullPair_whoseClientFormDropsTheRefreshToken() {
        UUID id = UUID.randomUUID();
        when(refreshTokens.rotate("old")).thenReturn(new RefreshTokenService.Rotation(id, "new"));
        when(userRepository.findById(id)).thenReturn(Optional.of(
                User.builder().id(id).phone("+2290197000322").status(UserStatus.ACTIVE).build()));

        AuthResponse full = service.refresh(new RefreshRequest("old"));
        AuthResponse exposed = full.withoutRefreshToken();

        assertThat(full.refreshToken()).isEqualTo("new");
        assertThat(exposed.refreshToken()).isNull();
        assertThat(exposed.accessToken()).isEqualTo(full.accessToken());
        assertThat(exposed.user()).isSameAs(full.user());
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
