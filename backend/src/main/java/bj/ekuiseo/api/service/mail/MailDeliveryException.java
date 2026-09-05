package bj.ekuiseo.api.service.mail;

/** Levee quand l'envoi effectif d'un e-mail echoue (SMTP injoignable, authentification refusee, etc.). */
public class MailDeliveryException extends RuntimeException {
    public MailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
