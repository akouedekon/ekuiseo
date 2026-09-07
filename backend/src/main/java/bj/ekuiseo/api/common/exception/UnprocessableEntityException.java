package bj.ekuiseo.api.common.exception;

/**
 * 422 Unprocessable Entity : requete bien formee mais refusee par une regle metier de
 * plafond (ex. plus de 10 alertes de recherche actives, constat F524). Traduite en
 * ProblemDetail par le controleur concerne (TripAlertController) tant que
 * GlobalExceptionHandler ne la connait pas.
 */
public class UnprocessableEntityException extends RuntimeException {
    public UnprocessableEntityException(String message) {
        super(message);
    }
}
