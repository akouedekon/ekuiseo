package bj.ekuiseo.api.service.sms;

import bj.ekuiseo.api.common.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Journalise le SMS au lieu de l'envoyer (developpement, tests).
 *
 * <p>Numero masque et codes remplaces par des etoiles par defaut ; le clair n'est
 * retabli que par {@code ekuiseo.otp.log-plain-codes=true} (developpement local).</p>
 */
public class LoggingSmsGateway implements SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsGateway.class);

    private final boolean logPlainCodes;

    public LoggingSmsGateway(boolean logPlainCodes) {
        this.logPlainCodes = logPlainCodes;
        log.warn("LoggingSmsGateway actif : AUCUN SMS reel n'est envoye (ekuiseo.sms.mode=log). "
                + "A n'utiliser qu'en developpement.{}", logPlainCodes ? " Codes journalises EN CLAIR." : "");
    }

    @Override
    public void send(String phoneE164, String message) {
        if (logPlainCodes) {
            log.info("[SMS-STUB] a {} : {}", phoneE164, message);
        } else {
            log.info("[SMS-STUB] a {} : {}", Masking.phone(phoneE164), Masking.codes(message));
        }
    }
}
