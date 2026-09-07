package bj.ekuiseo.api.common;

import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 de l audit (constats F007/F427/F452/F542) : les erreurs de forme de la requete
 * donnent un 400/404/405 RFC 7807 explicite et plus jamais un 500 ; le vrai 500 porte un
 * identifiant court ({@code errorId}) ; le 429 porte {@code Retry-After}. MockMvc en mode
 * autonome sur un controleur factice : ni contexte Spring, ni base.
 */
class GlobalExceptionHandlerTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                // Comportement de Spring Boot 3 : une route inconnue leve une exception au lieu
                // d envoyer un 404 muet, ce qui permet au handler de produire un ProblemDetail.
                .addDispatcherServletCustomizer(ds -> ds.setThrowExceptionIfNoHandlerFound(true))
                .build();
    }

    @Test
    void invalidUuidInPath_isA400ProblemDetail_notA500() throws Exception {
        mvc.perform(get("/probe/items/pas-un-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(containsString("validation-error")))
                .andExpect(jsonPath("$.detail").value(containsString("id")))
                .andExpect(jsonPath("$.instance").value("/probe/items/pas-un-uuid"));
    }

    @Test
    void missingRequiredParameter_isA400() throws Exception {
        mvc.perform(get("/probe/count"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("'n'")));
    }

    @Test
    void wrongTypeParameter_isA400() throws Exception {
        mvc.perform(get("/probe/count").param("n", "douze"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("'n'")));
    }

    @Test
    void outOfRangeParameter_isA400() throws Exception {
        mvc.perform(get("/probe/count").param("n", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void malformedJsonBody_isA400_withoutJacksonInternals() throws Exception {
        mvc.perform(post("/probe/items").contentType(MediaType.APPLICATION_JSON).content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Le corps de la requete est absent ou mal forme"));
    }

    @Test
    void invalidBody_isA400_listingTheField() throws Exception {
        mvc.perform(post("/probe/items").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("name")));
    }

    @Test
    void unknownRoute_isA404ProblemDetail() throws Exception {
        mvc.perform(get("/probe/nulle-part"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(containsString("not-found")));
    }

    @Test
    void wrongMethod_isA405() throws Exception {
        mvc.perform(post("/probe/count").param("n", "1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("POST")));
    }

    @Test
    void unexpectedException_isA500_withAShortErrorId_andNoInternalMessage() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.errorId").value(matchesPattern("[0-9a-f]{8}")))
                .andExpect(jsonPath("$.detail").value(containsString("reference ")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("secret interne"))));
    }

    @Test
    void tooManyRequests_carriesRetryAfterSeconds() throws Exception {
        mvc.perform(get("/probe/throttled"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(containsString("too-many-requests")));
    }

    @Test
    void dataIntegrityViolation_isA409() throws Exception {
        mvc.perform(get("/probe/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(containsString("conflict")));
    }

    /** Controleur factice couvrant chaque famille d erreur de forme. */
    @RestController
    static class ProbeController {

        record Item(@NotBlank String name) {
        }

        @GetMapping("/probe/items/{id}")
        String item(@PathVariable UUID id) {
            return id.toString();
        }

        @PostMapping("/probe/items")
        String create(@Valid @RequestBody Item item) {
            return item.name();
        }

        @GetMapping("/probe/count")
        String count(@RequestParam @Min(1) int n) {
            return String.valueOf(n);
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("secret interne : connexion 10.0.0.5 refusee");
        }

        @GetMapping("/probe/throttled")
        String throttled() {
            throw new TooManyRequestsException("Trop de requetes", 42);
        }

        @GetMapping("/probe/duplicate")
        String duplicate() {
            throw new DataIntegrityViolationException("uq_bookings_trip_passenger_active");
        }
    }
}
