package bj.ekuiseo.api.common;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.common.exception.ServiceUnavailableException;
import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import bj.ekuiseo.api.common.exception.UnauthorizedException;
import bj.ekuiseo.api.service.payment.PaymentProviderUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.net.URI;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Traduit toutes les exceptions metier en reponses d'erreur RFC 7807
 * (application/problem+json) via {@link ProblemDetail}.
 *
 * <p>Phase 2 de l audit (constats F007/F427/F452/F439) : les erreurs de forme de Spring
 * MVC (parametre absent ou mal type, JSON illisible, contrainte violee sur un parametre,
 * methode ou format non supporte, route inconnue) ont chacune leur statut 400/404/405/406/415
 * et ne tombent plus dans le 500 generique. Le 500 est journalise en ERROR avec la methode,
 * le chemin et un identifiant court {@code errorId}, repris dans la reponse pour relier un
 * ticket a sa trace ; le 409 d integrite est journalise en WARN avec la cause SQL.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
     * deja annulee, on renvoie un 409 explicite plutot qu'un 500 generique. La cause SQL est
     * journalisee (sans pile) : une contrainte inattendue signale une validation manquante.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("Conflit d integrite sur {} {} : {}", req.getMethod(), req.getRequestURI(),
                ex.getMostSpecificCause().getMessage());
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

    /** 429 avec {@code Retry-After} (secondes restantes de la fenetre, constat F542). */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ProblemDetail> handleTooManyRequests(TooManyRequestsException ex, HttpServletRequest req) {
        ProblemDetail pd = build(HttpStatus.TOO_MANY_REQUESTS, "too-many-requests", ex.getMessage(), req);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    /** Fournisseur de paiement injoignable ou non conclusif (contrat A.11 : KkiapayUnavailableException en herite). */
    @ExceptionHandler(PaymentProviderUnavailableException.class)
    public ProblemDetail handleKkiapayUnavailable(PaymentProviderUnavailableException ex, HttpServletRequest req) {
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

    /* ------------------------------------------------------------ 400 : forme de la requete */

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "validation-error", detail, req);
    }

    /** Contraintes sur les parametres de methode (@Min, @DecimalMax... sur un @RequestParam), validation native de Spring MVC 6.1. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest req) {
        String detail = ex.getAllValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> r.getMethodParameter().getParameterName() + ": " + e.getDefaultMessage()))
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "validation-error", detail.isEmpty() ? "Parametre invalide" : detail, req);
    }

    /** Contraintes verifiees par un proxy @Validated (services, composants). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest req) {
        String detail = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "validation-error", detail, req);
    }

    /** Parametre de requete au mauvais type : UUID invalide, date mal formee, enum inconnue, nombre non numerique. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        String expected = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "valeur";
        return build(HttpStatus.BAD_REQUEST, "validation-error",
                "Le parametre '" + ex.getName() + "' est invalide (" + expected + " attendu)", req);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "validation-error",
                "Le parametre '" + ex.getParameterName() + "' est obligatoire", req);
    }

    /** Corps JSON absent, illisible, ou valeur d enum inconnue (le message Jackson n est pas expose : il cite les classes). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "validation-error",
                "Le corps de la requete est absent ou mal forme", req);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large", "La requete est trop volumineuse", req);
    }

    /** Multipart sans la partie attendue, ou corps multipart illisible (televersement des pieces d identite, V20). */
    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ProblemDetail handleMultipart(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "validation-error",
                ex instanceof MissingServletRequestPartException
                        ? "Fichier absent : partie multipart '" + ((MissingServletRequestPartException) ex).getRequestPartName() + "' attendue"
                        : "Corps multipart illisible", req);
    }

    /** Fonctionnalite non activee sur ce serveur (ex. stockage des pieces d identite sans cle configuree). */
    @ExceptionHandler(ServiceUnavailableException.class)
    public ProblemDetail handleServiceUnavailable(ServiceUnavailableException ex, HttpServletRequest req) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable", ex.getMessage(), req);
    }

    /* ------------------------------------------------------------ 404 / 405 / 406 / 415 */

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ProblemDetail handleNoRoute(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "not-found", "Ressource introuvable", req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed",
                "Methode " + ex.getMethod() + " non supportee sur cette ressource", req);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ProblemDetail handleNotAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest req) {
        // Le corps est produit en application/problem+json : un client qui n accepte que
        // text/csv le lira quand meme, c est le comportement standard de Spring pour ce cas.
        return build(HttpStatus.NOT_ACCEPTABLE, "not-acceptable",
                "Format demande (Accept) non disponible pour cette ressource", req);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type",
                "Format du corps (Content-Type) non supporte : application/json attendu", req);
    }

    /* ------------------------------------------------------------ 500 : vrai bug */

    /**
     * Tout ce qui precede n a pas reconnu : un bug. Journalise en ERROR avec la pile, la
     * methode, le chemin et un identifiant court repris dans la reponse ({@code errorId}),
     * pour retrouver la trace a partir d un ticket utilisateur. Le message interne n est
     * jamais expose.
     */
    /**
     * Le client a ferme la connexion pendant l ecriture de la reponse (navigation, onglet ferme,
     * reseau mobile coupe) : rien a corriger cote serveur, rien a repondre non plus. Journalise en
     * debug seulement, pour que le journal d erreurs ne se remplisse pas de « Broken pipe ».
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, ClientAbortException.class})
    public void handleClientAbort(Exception ex, HttpServletRequest req) {
        log.debug("Connexion fermee par le client sur {} {} : {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex, HttpServletRequest req) {
        String errorId = newErrorId();
        log.error("Erreur non geree [{}] sur {} {}", errorId, req.getMethod(), req.getRequestURI(), ex);
        ProblemDetail pd = build(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "Une erreur inattendue est survenue (reference " + errorId + ")", req);
        pd.setProperty("errorId", errorId);
        return pd;
    }

    /** 8 caracteres hexadecimaux : assez pour retrouver une trace, assez court pour etre dicte au support. */
    static String newErrorId() {
        return String.format("%08x", ThreadLocalRandom.current().nextInt());
    }

    private ProblemDetail build(HttpStatus status, String type, String detail, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("https://ekuiseo.bj/problems/" + type));
        pd.setInstance(URI.create(req.getRequestURI()));
        return pd;
    }
}
