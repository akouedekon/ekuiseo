package bj.ekuiseo.api.service.payment;

/**
 * Le fournisseur de paiement est injoignable (reseau, delai) ou n a pas pu conclure : traduite
 * en 503 par {@link bj.ekuiseo.api.common.GlobalExceptionHandler}, ce qui fait rejouer le
 * webhook par l agregateur. Les implementations concretes (KkiapayUnavailableException) en
 * heritent ; les services metier ne connaissent que celle-ci.
 */
public class PaymentProviderUnavailableException extends RuntimeException {
    public PaymentProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
