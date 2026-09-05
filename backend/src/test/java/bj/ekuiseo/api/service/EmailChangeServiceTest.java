package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.domain.OtpCode;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.dto.auth.OtpRequestResponse;
import bj.ekuiseo.api.mapper.UserMapper;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.service.mail.MailGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
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

/** Constats F013/F538/F104 : changement d e-mail en deux temps, unicite, avis a l ancienne adresse. */
class EmailChangeServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final OtpCodeService otpCodes = mock(OtpCodeService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final List<String[]> mails = new ArrayList<>();
    private final MailGateway mailGateway = (to, subject, text) -> mails.add(new String[] {to, subject, text});
    private final EmailChangeService service = new EmailChangeService(userRepository, otpCodes,
            new OtpRateLimiter(3, 10), mailGateway, auditService, userMapper);
    private final UUID userId = UUID.randomUUID();
    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(userId).phone("+2290197000322").email("ancienne@example.com").firstName("Afi")
                .lastName("Test").passwordHash("x").emailVerified(true).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(otpCodes.issue(anyString(), anyString(), anyString())).thenReturn("654321");
    }

    @Test
    void request_storesPendingAddressAndSendsCodeToTheNewAddressOnly() {
        when(userRepository.existsByEmailIgnoreCaseAndIdNot("nouvelle@example.com", userId)).thenReturn(false);

        OtpRequestResponse res = service.request(userId, " nouvelle@example.com ");

        assertThat(res.destination()).isEqualTo("no***@example.com");
        assertThat(user.getPendingEmail()).isEqualTo("nouvelle@example.com");
        assertThat(user.getEmail()).isEqualTo("ancienne@example.com");
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0)[0]).isEqualTo("nouvelle@example.com");
        assertThat(mails.get(0)[2]).contains("654321");
        verify(otpCodes).issue("+2290197000322", "CHANGE_EMAIL", "EMAIL");
    }

    @Test
    void request_refusesTakenOrIdenticalAddress() {
        when(userRepository.existsByEmailIgnoreCaseAndIdNot("prise@example.com", userId)).thenReturn(true);
        assertThatThrownBy(() -> service.request(userId, "prise@example.com")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.request(userId, "ANCIENNE@example.com")).isInstanceOf(BadRequestException.class);
        assertThat(mails).isEmpty();
    }

    @Test
    void confirm_switchesAddressNotifiesOldOneAndAudits() {
        user.setPendingEmail("nouvelle@example.com");
        when(otpCodes.consume("+2290197000322", "CHANGE_EMAIL", "654321")).thenReturn(OtpCode.builder().build());
        when(userRepository.existsByEmailIgnoreCaseAndIdNot("nouvelle@example.com", userId)).thenReturn(false);

        service.confirm(userId, "654321");

        assertThat(user.getEmail()).isEqualTo("nouvelle@example.com");
        assertThat(user.getPendingEmail()).isNull();
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0)[0]).isEqualTo("ancienne@example.com");
        assertThat(mails.get(0)[2]).contains("no***@example.com");
        verify(auditService).log(eq(userId), eq("USER_EMAIL_CHANGED"), eq("user"), eq(userId), any());
    }

    @Test
    void confirm_withoutPendingRequest_orWrongCode_changesNothing() {
        assertThatThrownBy(() -> service.confirm(userId, "654321")).isInstanceOf(BadRequestException.class);

        user.setPendingEmail("nouvelle@example.com");
        when(otpCodes.consume("+2290197000322", "CHANGE_EMAIL", "000000")).thenThrow(new BadRequestException("Code incorrect"));
        assertThatThrownBy(() -> service.confirm(userId, "000000")).isInstanceOf(BadRequestException.class);
        assertThat(user.getEmail()).isEqualTo("ancienne@example.com");
        verify(auditService, never()).log(any(), anyString(), anyString(), any(), any());
    }
}
