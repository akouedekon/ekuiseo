package bj.ekuiseo.api.service.kkiapay;

import bj.ekuiseo.api.service.payment.PaymentProviderUnavailableException;

/**
 * Levee quand Kkiapay est injoignable (reseau/timeout), par opposition a une reponse
 * HTTP d'erreur explicite (auquel cas on retourne un resultat "echec" plutot que de
 * lever une exception, cf. {@link KkiapayHttpGateway}). Specialisation Kkiapay de
 * {@link PaymentProviderUnavailableException}, traduite en 503 par
 * {@link bj.ekuiseo.api.common.GlobalExceptionHandler}.
 */
public class KkiapayUnavailableException extends PaymentProviderUnavailableException {
    public KkiapayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
