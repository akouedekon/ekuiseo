package bj.ekuiseo.api.service.mail;

/**
 * Abstraction d'envoi d'e-mail (codes de verification, recus). Deux implementations :
 * {@link LoggingMailGateway} (journalise, developpement) et {@link SmtpMailGateway}
 * (serveur SMTP configure via {@code spring.mail.*}, production).
 */
public interface MailGateway {
    void send(String to, String subject, String text);
}
