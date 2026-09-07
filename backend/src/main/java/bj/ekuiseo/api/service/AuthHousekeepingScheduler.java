package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.repository.RefreshTokenRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Menage des donnees d authentification :
 * <ul>
 *   <li>comptes crees a l inscription mais jamais verifies ({@code PENDING_VERIFICATION})
 *       supprimes apres 24 h, ce qui libere le numero (constat F023) ;</li>
 *   <li>jetons de rafraichissement expires depuis plus de 7 jours (la ligne des jetons
 *       revoques est conservee 7 jours pour la detection de reutilisation).</li>
 * </ul>
 * Les codes a usage unique expires sont purges par {@link RetentionScheduler}
 * ({@code ekuiseo.retention.otp-hours}), source unique de cette duree.
 */
@Component
public class AuthHousekeepingScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuthHousekeepingScheduler.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TransactionTemplate transaction;
    private final long pendingTtlHours;

    public AuthHousekeepingScheduler(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
                                     PlatformTransactionManager transactionManager,
                                     @Value("${ekuiseo.auth.pending-account-ttl-hours:24}") long pendingTtlHours) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.transaction = new TransactionTemplate(transactionManager);
        this.pendingTtlHours = pendingTtlHours;
    }

    @Scheduled(fixedRate = 3_600_000, initialDelay = 120_000)
    public void run() {
        try {
            Instant now = Instant.now();
            int pending = transaction.execute(status -> userRepository.deleteByStatusAndCreatedAtBefore(
                    UserStatus.PENDING_VERIFICATION, now.minus(pendingTtlHours, ChronoUnit.HOURS)));
            int tokens = transaction.execute(status -> refreshTokenRepository.deleteExpiredBefore(
                    now.minus(7, ChronoUnit.DAYS)));
            if (pending > 0 || tokens > 0) {
                log.info("Menage auth : {} compte(s) jamais verifie(s), {} jeton(s) expire(s) supprimes", pending, tokens);
            }
        } catch (RuntimeException ex) {
            log.error("Menage auth : echec de l execution", ex);
        }
    }
}
