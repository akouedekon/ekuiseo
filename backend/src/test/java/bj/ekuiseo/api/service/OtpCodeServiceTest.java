package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.OtpCode;
import bj.ekuiseo.api.repository.OtpCodeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constat F536 : le compteur d essais est persiste et les usages (LOGIN / CHANGE_EMAIL) sont etanches. */
class OtpCodeServiceTest {

    private final OtpCodeRepository repository = mock(OtpCodeRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final OtpCodeService service = new OtpCodeService(repository, encoder, 5);

    @Test
    void issueHashesTheCodeAndStoresPurposeAndChannel() {
        when(repository.save(any(OtpCode.class))).thenAnswer(inv -> inv.getArgument(0));

        String code = service.issue("+2290197000322", OtpCodeService.PURPOSE_CHANGE_EMAIL, "EMAIL");

        ArgumentCaptor<OtpCode> saved = ArgumentCaptor.forClass(OtpCode.class);
        verify(repository).save(saved.capture());
        assertThat(code).matches("\\d{6}");
        assertThat(saved.getValue().getCodeHash()).isNotEqualTo(code);
        assertThat(encoder.matches(code, saved.getValue().getCodeHash())).isTrue();
        assertThat(saved.getValue().getPurpose()).isEqualTo("CHANGE_EMAIL");
        assertThat(saved.getValue().getChannel()).isEqualTo("EMAIL");
    }

    @Test
    void wrongCodeIncrementsAttempts_andNoRollback() throws Exception {
        OtpCode otp = otp("h", 3);
        when(repository.findFirstByPhoneAndPurposeAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                eq("+2290197000322"), eq("LOGIN"), any())).thenReturn(Optional.of(otp));
        when(repository.save(any(OtpCode.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.consume("+2290197000322", "LOGIN", "000000"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("incorrect");
        assertThat(otp.getAttempts()).isEqualTo(4);
        verify(repository).save(otp);

        Transactional tx = OtpCodeService.class.getMethod("consume", String.class, String.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(tx.noRollbackFor()).contains(BadRequestException.class);
    }

    @Test
    void burntCodeIsConsumedAndRefused() {
        OtpCode otp = otp("h", 5);
        when(repository.findFirstByPhoneAndPurposeAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                eq("+2290197000322"), eq("LOGIN"), any())).thenReturn(Optional.of(otp));
        when(repository.save(any(OtpCode.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.consume("+2290197000322", "LOGIN", "123456"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("maximal");
        assertThat(otp.getConsumedAt()).isNotNull();
    }

    @Test
    void aCodeIssuedForAnotherPurposeIsNotFound() {
        when(repository.findFirstByPhoneAndPurposeAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                eq("+2290197000322"), eq("LOGIN"), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.consume("+2290197000322", "LOGIN", "123456"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Aucun code valide");
    }

    private static OtpCode otp(String hash, int attempts) {
        return OtpCode.builder().id(UUID.randomUUID()).phone("+2290197000322").codeHash(hash).purpose("LOGIN")
                .channel("EMAIL").expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES)).attempts(attempts).build();
    }
}
