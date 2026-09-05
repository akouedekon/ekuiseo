package bj.ekuiseo.api.service.mail;

import bj.ekuiseo.api.common.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Journalise l'e-mail au lieu de l'envoyer (developpement, tests).
 *
 * <p>Par defaut, le destinataire est masque et les codes a 4-8 chiffres sont
 * remplaces par des etoiles : un journal n'est pas un canal de livraison, et
 * quiconque lit les logs ne doit pas pouvoir ouvrir la session d'un tiers. En
 * developpement local, {@code ekuiseo.otp.log-plain-codes=true} retablit le code
 * en clair (jamais pose par docker-compose.prod.yml).</p>
 */
public class LoggingMailGateway implements MailGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailGateway.class);

    private final boolean logPlainCodes;

    public LoggingMailGateway(boolean logPlainCodes) {
        this.logPlainCodes = logPlainCodes;
        log.warn("LoggingMailGateway actif : AUCUN e-mail reel n'est envoye (ekuiseo.mail.mode=log). "
                + "A n'utiliser qu'en developpement.{}", logPlainCodes ? " Codes journalises EN CLAIR." : "");
    }

    @Override
    public void send(String to, String subject, String text) {
        if (logPlainCodes) {
            log.info("[MAIL-STUB] a {} | {} | {}", to, subject, text);
        } else {
            log.info("[MAIL-STUB] a {} | {} | {}", Masking.email(to), Masking.codes(subject), Masking.codes(text));
        }
    }
}
