package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import bj.ekuiseo.api.domain.OtpCode;
import bj.ekuiseo.api.repository.OtpCodeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Codes a usage unique : emission (6 chiffres, haches, 5 minutes) et consommation avec
 * compteur d essais persistant. Partage par la connexion ({@code LOGIN}) et le
 * changement d e-mail ({@code CHANGE_EMAIL}) et la suppression du compte
 * ({@code DELETE_ACCOUNT}) : un code emis pour un usage ne vaut jamais pour un autre.
 *
 * <p>Phase 2 (constats F540/F512) : emettre un code invalide les codes actifs du meme
 * numero pour le meme usage (un seul code valable a la fois, le dernier), et un numero ne
 * recoit pas plus de {@code ekuiseo.otp.max-per-day} codes par 24 h, compte en base (429).</p>
 *
 * <p>Phase 3 (constat F543) : la limite glissante (3 codes / 10 minutes par numero,
 * {@code ekuiseo.sms.otp.rate-limit.*}) est elle aussi comptee en base sur otp_codes :
 * durable a un redeploiement, et exacte puisque la ligne d un envoi echoue est annulee
 * avec la transaction. {@link OtpRateLimiter} (memoire) reste un premier filtre.</p>
 */
@Service
public class OtpCodeService {

    public static final String PURPOSE_LOGIN = "LOGIN";
    public static final String PURPOSE_CHANGE_EMAIL = "CHANGE_EMAIL";
    /** Confirmation de la suppression du compte (AccountDeletionService, constat F507). */
    public static final String PURPOSE_DELETE_ACCOUNT = "DELETE_ACCOUNT";
    static final Duration DAILY_WINDOW = Duration.ofHours(24);

    private final OtpCodeRepository otpCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final int maxAttempts;
    private final int maxPerDay;
    private final int maxPerWindow;
    private final Duration window;
    private final SecureRandom random = new SecureRandom();

    /** Constructeur Spring : explicite car un second constructeur (tests) existe. */
    @org.springframework.beans.factory.annotation.Autowired
    public OtpCodeService(OtpCodeRepository otpCodeRepository, PasswordEncoder passwordEncoder,
                          @Value("${ekuiseo.sms.otp.max-attempts:5}") int maxAttempts,
                          @Value("${ekuiseo.otp.max-per-day:20}") int maxPerDay,
                          @Value("${ekuiseo.sms.otp.rate-limit.max-requests:3}") int maxPerWindow,
                          @Value("${ekuiseo.sms.otp.rate-limit.window-minutes:10}") long windowMinutes) {
        this.otpCodeRepository = otpCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.maxAttempts = maxAttempts;
        this.maxPerDay = maxPerDay;
        this.maxPerWindow = maxPerWindow;
        this.window = Duration.ofMinutes(Math.max(1, windowMinutes));
    }

    /** Constructeur de test : limite glissante par defaut (3 / 10 min). */
    OtpCodeService(OtpCodeRepository otpCodeRepository, PasswordEncoder passwordEncoder, int maxAttempts, int maxPerDay) {
        this(otpCodeRepository, passwordEncoder, maxAttempts, maxPerDay, 3, 10);
    }

    /**
     * Genere, hache et enregistre un code ; renvoie le code en clair pour l envoi (jamais
     * journalise). Les codes actifs du meme (numero, usage) sont invalides d abord ; 429 au
     * dela de la limite glissante (fenetre courte) ou du plafond quotidien du numero.
     */
    @Transactional
    public String issue(String phone, String purpose, String channel) {
        Instant now = Instant.now();
        long issuedInWindow = otpCodeRepository.countByPhoneAndCreatedAtAfter(phone, now.minus(window));
        if (issuedInWindow >= maxPerWindow) {
            throw new TooManyRequestsException("Trop de demandes de code pour ce numero, reessayez dans quelques minutes.",
                    window.getSeconds());
        }
        long issuedToday = otpCodeRepository.countByPhoneAndCreatedAtAfter(phone, now.minus(DAILY_WINDOW));
        if (issuedToday >= maxPerDay) {
            throw new TooManyRequestsException("Trop de codes demandes pour ce numero aujourd hui : reessayez demain "
                    + "ou contactez le support");
        }
        otpCodeRepository.consumeActive(phone, purpose, now);
        String code = String.format("%06d", random.nextInt(1_000_000));
        OtpCode otp = OtpCode.builder()
                .phone(phone)
                .codeHash(passwordEncoder.encode(code))
                .purpose(purpose)
                .channel(channel)
                .expiresAt(now.plus(5, ChronoUnit.MINUTES))
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
