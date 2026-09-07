package bj.ekuiseo.api.common.exception;

/**
 * Levee quand une limite de debit metier est depassee (ex : trop de demandes d'OTP pour
 * un numero). {@code retryAfterSeconds} alimente l en-tete {@code Retry-After} de la
 * reponse 429 (constat F542) : le delai reel de la fenetre quand l appelant le connait,
 * sinon {@link #DEFAULT_RETRY_AFTER_SECONDS}.
 */
public class TooManyRequestsException extends RuntimeException {

    /** Delai annonce quand la fenetre exacte n est pas connue de l appelant. */
    public static final long DEFAULT_RETRY_AFTER_SECONDS = 60;

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message) {
        this(message, DEFAULT_RETRY_AFTER_SECONDS);
    }

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
