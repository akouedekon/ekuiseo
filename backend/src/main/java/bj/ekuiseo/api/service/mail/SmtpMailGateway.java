package bj.ekuiseo.api.service.mail;

import bj.ekuiseo.api.common.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Envoi via le serveur SMTP configure par Spring Boot ({@code spring.mail.host},
 * {@code spring.mail.port}, {@code spring.mail.username}, {@code spring.mail.password}).
 * Fonctionne avec n'importe quel relais SMTP authentifie : Brevo (gratuit jusqu'a 300
 * e-mails par jour), Gmail avec un mot de passe d'application, Mailgun, OVH, etc.
 */
public class SmtpMailGateway implements MailGateway {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailGateway.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpMailGateway(JavaMailSender mailSender, String from) {
        if (mailSender == null) {
            throw new IllegalStateException("ekuiseo.mail.mode=smtp requiert MAIL_HOST (spring.mail.host) : "
                    + "renseignez MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD et MAIL_FROM, ou repassez en mode=log.");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("ekuiseo.mail.mode=smtp requiert MAIL_FROM (adresse expeditrice).");
        }
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        try {
            mailSender.send(message);
        } catch (MailException ex) {
            log.error("Echec d'envoi de l'e-mail a {} ({})", Masking.email(to), Masking.codes(subject), ex);
            throw new MailDeliveryException("Impossible d'envoyer l'e-mail", ex);
        }
    }
}
