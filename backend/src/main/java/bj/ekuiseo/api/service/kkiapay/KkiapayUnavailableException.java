package bj.ekuiseo.api.service.kkiapay;

/**
 * Levee quand Kkiapay est injoignable (reseau/timeout), par opposition a une reponse
 * HTTP d'erreur explicite (auquel cas on retourne un resultat "echec" plutot que de
 * lever une exception, cf. {@link KkiapayHttpGateway}). Traduite en 503 par
 * {@link bj.ekuiseo.api.common.GlobalExceptionHandler}.
 */
public class KkiapayUnavailableException extends RuntimeException {
    public KkiapayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
