package bj.ekuiseo.api.service.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation de developpement : journalise le SMS au lieu de l'envoyer.
 * Active par defaut (ekuiseo.sms.mode=log) tant qu'aucun fournisseur SMS n'est
 * configure.
 */
public class LoggingSmsGateway implements SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsGateway.class);

    public LoggingSmsGateway() {
        log.warn("LoggingSmsGateway actif : AUCUN SMS reel n'est envoye (ekuiseo.sms.mode=log). "
                + "A n'utiliser qu'en developpement.");
    }

    @Override
    public void send(String phoneE164, String message) {
        log.info("[SMS-STUB] a {} : {}", phoneE164, message);
    }
}
