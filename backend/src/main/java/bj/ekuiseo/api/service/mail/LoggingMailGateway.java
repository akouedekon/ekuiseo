package bj.ekuiseo.api.service.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Journalise l'e-mail au lieu de l'envoyer (developpement, tests). */
public class LoggingMailGateway implements MailGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailGateway.class);

    public LoggingMailGateway() {
        log.warn("LoggingMailGateway actif : AUCUN e-mail reel n'est envoye (ekuiseo.mail.mode=log). "
                + "A n'utiliser qu'en developpement.");
    }

    @Override
    public void send(String to, String subject, String text) {
        log.info("[MAIL-STUB] a {} | {} | {}", to, subject, text);
    }
}
