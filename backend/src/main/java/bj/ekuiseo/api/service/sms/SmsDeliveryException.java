package bj.ekuiseo.api.service.sms;

/** Levee quand l'envoi effectif d'un SMS echoue (reseau, fournisseur en panne, etc.). */
public class SmsDeliveryException extends RuntimeException {
    public SmsDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
