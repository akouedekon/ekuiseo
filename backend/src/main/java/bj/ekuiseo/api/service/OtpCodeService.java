package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.OtpCode;
import bj.ekuiseo.api.repository.OtpCodeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Codes a usage unique : emission (6 chiffres, haches, 5 minutes) et consommation avec
 * compteur d essais persistant. Partage par la connexion ({@code LOGIN}) et le
 * changement d e-mail ({@code CHANGE_EMAIL}) et la suppression du compte
 * ({@code DELETE_ACCOUNT}) : un code emis pour un usage ne vaut jamais pour un autre.
 */
@Service
public class OtpCodeService {

    public static final String PURPOSE_LOGIN = "LOGIN";
    public static final String PURPOSE_CHANGE_EMAIL = "CHANGE_EMAIL";
    /** Confirmation de la suppression du compte (AccountDeletionService, constat F507). */
    public static final String PURPOSE_DELETE_ACCOUNT = "DELETE_ACCOUNT";

    private final OtpCodeRepository otpCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final int maxAttempts;
    private final SecureRandom random = new SecureRandom();

    public OtpCodeService(OtpCodeRepository otpCodeRepository, PasswordEncoder passwordEncoder,
                          @Value("${ekuiseo.sms.otp.max-attempts:5}") int maxAttempts) {
        this.otpCodeRepository = otpCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.maxAttempts = maxAttempts;
    }

    /** Genere, hache et enregistre un code ; renvoie le code en clair pour l envoi (jamais journalise). */
    @Transactional
    public String issue(String phone, String purpose, String channel) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        OtpCode otp = OtpCode.builder()
                .phone(phone)
                .codeHash(passwordEncoder.encode(code))
                .purpose(purpose)
                .channel(channel)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();
        otpCodeRepository.save(otp);
        return code;
    }

    /**
     * Verifie et consomme le dernier code valide de ce numero pour cet usage.
     * noRollbackFor : un code faux DOIT laisser en base l increment de attempts (et la
     * consommation du code grille) ; sans cela le rollback annulait le compteur et la
     * limite de 5 essais etait inoperante (constat F536 de l audit).
     */
    @Transactional(noRollbackFor = BadRequestException.class)
    public OtpCode consume(String phone, String purpose, String code) {
        OtpCode otp = otpCodeRepository
                .findFirstByPhoneAndPurposeAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(phone, purpose, Instant.now())
                .orElseThrow(() -> new BadRequestException("Aucun code valide pour ce numero, redemandez un code"));
        if (otp.getAttempts() >= maxAttempts) {
            // Code grille par trop de tentatives incorrectes (regle metier n.8) : on l invalide
            // definitivement plutot que de laisser expirer normalement.
            otp.setConsumedAt(Instant.now());
            otpCodeRepository.save(otp);
            throw new BadRequestException("Nombre maximal de tentatives atteint pour ce code, redemandez un code");
        }
        if (!passwordEncoder.matches(code, otp.getCodeHash())) {
            otp.setAttempts(otp.getAttempts() + 1);
            otpCodeRepository.save(otp);
            throw new BadRequestException("Code incorrect");
        }
        otp.setConsumedAt(Instant.now());
        return otpCodeRepository.save(otp);
    }
}
