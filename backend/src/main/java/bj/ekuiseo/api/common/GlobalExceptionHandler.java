package bj.ekuiseo.api.common;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import bj.ekuiseo.api.common.exception.UnauthorizedException;
import bj.ekuiseo.api.service.kkiapay.KkiapayUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * Traduit toutes les exceptions metier en reponses d'erreur RFC 7807
 * (application/problem+json) via {@link ProblemDetail}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "not-found", ex.getMessage(), req);
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "conflict", ex.getMessage(), req);
    }

    /**
     * Violation d'une contrainte d'unicite en base : typiquement deux requetes concurrentes du
     * meme passager sur le meme trajet (uq_bookings_trip_passenger_active). La transaction est
     * deja annulee, on renvoie un 409 explicite plutot qu'un 500 generique.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(org.springframework.dao.DataIntegrityViolationException ex,
                                             HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "conflict",
                "Cette operation entre en conflit avec une donnee existante.", req);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail handleForbidden(ForbiddenException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "forbidden", ex.getMessage(), req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "forbidden", "Acces refuse", req);
    }

    @ExceptionHandler({BadRequestException.class, IllegalArgumentException.class})
    public ProblemDetail handleBadRequest(RuntimeException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "bad-request", ex.getMessage(), req);
    }

    @ExceptionHandler({UnauthorizedException.class, BadCredentialsException.class})
    public ProblemDetail handleUnauthorized(RuntimeException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, "unauthorized", ex.getMessage(), req);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ProblemDetail handleTooManyRequests(TooManyRequestsException ex, HttpServletRequest req) {
        return build(HttpStatus.TOO_MANY_REQUESTS, "too-many-requests", ex.getMessage(), req);
    }

    @ExceptionHandler(KkiapayUnavailableException.class)
    public ProblemDetail handleKkiapayUnavailable(KkiapayUnavailableException ex, HttpServletRequest req) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "upstream-unavailable",
                "Le service de paiement est temporairement indisponible, reessayez plus tard.", req);
    }

    /** Fournisseur SMS en panne ou mal configure : 503 explicite plutot qu'un 500 muet (le detail est journalise). */
    @ExceptionHandler(bj.ekuiseo.api.service.sms.SmsDeliveryException.class)
    public ProblemDetail handleSmsUnavailable(bj.ekuiseo.api.service.sms.SmsDeliveryException ex,
                                              HttpServletRequest req) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "sms-unavailable",
                "L'envoi du SMS est impossible pour l'instant, reessayez dans quelques minutes.", req);
    }

    @ExceptionHandler(bj.ekuiseo.api.service.mail.MailDeliveryException.class)
    public ProblemDetail handleMailUnavailable(bj.ekuiseo.api.service.mail.MailDeliveryException ex,
                                               HttpServletRequest req) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "mail-unavailable",
                "L envoi de l e-mail est impossible pour l instant, reessayez dans quelques minutes.", req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "validation-error", detail, req);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Une erreur inattendue est survenue", req);
    }

    private ProblemDetail build(HttpStatus status, String type, String detail, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("https://ekuiseo.bj/problems/" + type));
        pd.setInstance(URI.create(req.getRequestURI()));
        return pd;
    }
}
