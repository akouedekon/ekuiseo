package bj.ekuiseo.api.common.exception;

/** Levee quand une limite de debit metier est depassee (ex : trop de demandes d'OTP pour un numero). */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
