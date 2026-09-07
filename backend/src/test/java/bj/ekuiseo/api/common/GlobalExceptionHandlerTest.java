package bj.ekuiseo.api.common;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import bj.ekuiseo.api.common.exception.UnauthorizedException;
import bj.ekuiseo.api.service.kkiapay.KkiapayUnavailableException;
import bj.ekuiseo.api.service.mail.MailDeliveryException;
import bj.ekuiseo.api.service.sms.SmsDeliveryException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un test par exception traduite par {@link GlobalExceptionHandler} (constat F434) :
 * statut, {@code type} stable (https://ekuiseo.bj/problems/...), {@code detail} et
 * {@code instance} = URI demandee. Les exceptions techniques (Kkiapay, SMS, e-mail,
 * generique) ne doivent jamais laisser filtrer leur message interne.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/bookings/42/cancel");

    @Test
    void notFound_is404() {
        ProblemDetail pd = handler.handleNotFound(new NotFoundException("Reservation introuvable"), request);
        assertProblem(pd, HttpStatus.NOT_FOUND, "not-found", "Reservation introuvable");
    }

    @Test
    void conflict_is409() {
        ProblemDetail pd = handler.handleConflict(new ConflictException("Plus de place disponible"), request);
        assertProblem(pd, HttpStatus.CONFLICT, "conflict", "Plus de place disponible");
    }

    /** Doublon en base (ex : uq_bookings_trip_passenger_active) : 409 generique, sans le message SQL. */
    @Test
    void dataIntegrityViolation_is409_withoutSqlDetails() {
        ProblemDetail pd = handler.handleDataIntegrity(
                new DataIntegrityViolationException("ERROR: duplicate key value violates unique constraint \"uq_bookings_trip_passenger_active\""),
                request);
        assertProblem(pd, HttpStatus.CONFLICT, "conflict", "Cette operation entre en conflit avec une donnee existante.");
        assertThat(pd.getDetail()).doesNotContain("uq_bookings");
    }

    @Test
    void forbidden_is403() {
        ProblemDetail pd = handler.handleForbidden(new ForbiddenException("Vous n etes pas le conducteur de ce trajet"), request);
        assertProblem(pd, HttpStatus.FORBIDDEN, "forbidden", "Vous n etes pas le conducteur de ce trajet");
    }

    @Test
    void accessDenied_is403_withGenericDetail() {
        ProblemDetail pd = handler.handleAccessDenied(new AccessDeniedException("Access is denied"), request);
        assertProblem(pd, HttpStatus.FORBIDDEN, "forbidden", "Acces refuse");
    }

    @Test
    void badRequest_is400() {
        ProblemDetail pd = handler.handleBadRequest(new BadRequestException("Code invalide ou expire"), request);
        assertProblem(pd, HttpStatus.BAD_REQUEST, "bad-request", "Code invalide ou expire");
    }

    @Test
    void illegalArgument_is400() {
        ProblemDetail pd = handler.handleBadRequest(new IllegalArgumentException("Numero de telephone invalide"), request);
        assertProblem(pd, HttpStatus.BAD_REQUEST, "bad-request", "Numero de telephone invalide");
    }

    @Test
    void unauthorized_is401() {
        ProblemDetail pd = handler.handleUnauthorized(new UnauthorizedException("Compte suspendu"), request);
        assertProblem(pd, HttpStatus.UNAUTHORIZED, "unauthorized", "Compte suspendu");
    }

    @Test
    void badCredentials_is401() {
        ProblemDetail pd = handler.handleUnauthorized(new BadCredentialsException("Jeton invalide"), request);
        assertProblem(pd, HttpStatus.UNAUTHORIZED, "unauthorized", "Jeton invalide");
    }

    @Test
    void tooManyRequests_is429() {
        ProblemDetail pd = handler.handleTooManyRequests(new TooManyRequestsException("Trop de demandes de code"), request);
        assertProblem(pd, HttpStatus.TOO_MANY_REQUESTS, "too-many-requests", "Trop de demandes de code");
    }

    @Test
    void kkiapayUnavailable_is503_andHidesInternalMessage() {
        ProblemDetail pd = handler.handleKkiapayUnavailable(
                new KkiapayUnavailableException("GET https://api.kkiapay.me/... timeout", new RuntimeException("socket")), request);
        assertProblem(pd, HttpStatus.SERVICE_UNAVAILABLE, "upstream-unavailable",
                "Le service de paiement est temporairement indisponible, reessayez plus tard.");
        assertThat(pd.getDetail()).doesNotContain("kkiapay.me");
    }

    @Test
    void smsUnavailable_is503_andHidesProviderMessage() {
        ProblemDetail pd = handler.handleSmsUnavailable(new SmsDeliveryException("Twilio 21211 Invalid To", null), request);
        assertProblem(pd, HttpStatus.SERVICE_UNAVAILABLE, "sms-unavailable",
                "L'envoi du SMS est impossible pour l'instant, reessayez dans quelques minutes.");
        assertThat(pd.getDetail()).doesNotContain("Twilio");
    }

    @Test
    void mailUnavailable_is503_andHidesRelayMessage() {
        ProblemDetail pd = handler.handleMailUnavailable(new MailDeliveryException("smtp.example.test:587 refused", null), request);
        assertProblem(pd, HttpStatus.SERVICE_UNAVAILABLE, "mail-unavailable",
                "L envoi de l e-mail est impossible pour l instant, reessayez dans quelques minutes.");
        assertThat(pd.getDetail()).doesNotContain("smtp");
    }

    @Test
    void validation_is400_andListsEveryFieldError() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "otpRegisterRequest");
        binding.addError(new FieldError("otpRegisterRequest", "email", "ne doit pas etre vide"));
        binding.addError(new FieldError("otpRegisterRequest", "phone", "Indiquez un numero de telephone"));
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("sampleHandler", String.class), 0);

        ProblemDetail pd = handler.handleValidation(new MethodArgumentNotValidException(parameter, binding), request);

        assertProblem(pd, HttpStatus.BAD_REQUEST, "validation-error",
                "email: ne doit pas etre vide; phone: Indiquez un numero de telephone");
    }

    @Test
    void anyOtherException_is500_withoutLeakingTheMessage() {
        ProblemDetail pd = handler.handleGeneric(new IllegalStateException("NullPointer dans BookingService ligne 42"), request);
        assertProblem(pd, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Une erreur inattendue est survenue");
        assertThat(pd.getDetail()).doesNotContain("BookingService");
    }

    private static void assertProblem(ProblemDetail pd, HttpStatus status, String type, String detail) {
        assertThat(pd.getStatus()).isEqualTo(status.value());
        assertThat(pd.getType()).isEqualTo(URI.create("https://ekuiseo.bj/problems/" + type));
        assertThat(pd.getDetail()).isEqualTo(detail);
        assertThat(pd.getInstance()).isEqualTo(URI.create("/api/v1/bookings/42/cancel"));
    }

    /** Sert uniquement de support a MethodParameter dans le test de validation. */
    @SuppressWarnings("unused")
    private void sampleHandler(String body) {
    }
}
