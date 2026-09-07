package bj.ekuiseo.api.common.exception;

/**
 * 503 : la fonctionnalite demandee n est pas activee ou son support est indisponible sur ce
 * serveur (ex. televersement des pieces d identite sans cle de chiffrement configuree).
 * Distinct des pannes de passerelles (Kkiapay, SMTP), qui ont leurs propres exceptions.
 */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}
