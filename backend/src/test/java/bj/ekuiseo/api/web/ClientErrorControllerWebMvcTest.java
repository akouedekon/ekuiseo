package bj.ekuiseo.api.web;

import bj.ekuiseo.api.web.controller.ClientErrorController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /api/v1/client-errors} (constat F440) : public, valide, borne par IP. */
@WebMvcTest(controllers = ClientErrorController.class)
@TestPropertySource(properties = {
        "ekuiseo.rate-limit.client-errors.max-requests=2",
        "ekuiseo.rate-limit.client-errors.window-seconds=600"
})
class ClientErrorControllerWebMvcTest extends AbstractWebMvcTest {

    private static final String PATH = "/api/v1/client-errors";

    private String body(Map<String, Object> fields) throws Exception {
        return objectMapper.writeValueAsString(fields);
    }

    @Test
    void anonymousReport_isAccepted() throws Exception {
        mockMvc.perform(fromNewIp(post(PATH)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("message", "Cannot read properties of undefined", "source", "componentDidCatch",
                                "route", "/admin/verifications", "version", "abc123", "userAgent", "Safari",
                                "stack", "TypeError: x\n    at f (app.js:1:2)", "occurredAt", "2026-09-09T19:00:00Z"))))
                .andExpect(status().isAccepted());
    }

    @Test
    void invalidReport_is400() throws Exception {
        // Message vide : le rapport n apporte rien, refuse.
        mockMvc.perform(fromNewIp(post(PATH)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("message", "", "source", "window.onerror"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        // Pile demesuree : bornee a 4 000 caracteres.
        mockMvc.perform(fromNewIp(post(PATH)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("message", "x", "source", "s", "stack", "y".repeat(5000)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sameIp_isRateLimited() throws Exception {
        var request = post(PATH).header("X-Real-IP", "203.0.113.77").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("message", "boum", "source", "unhandledrejection")));
        mockMvc.perform(request).andExpect(status().isAccepted());
        mockMvc.perform(request).andExpect(status().isAccepted());
        mockMvc.perform(request)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void oneLine_flattensControlCharacters() {
        assertThat(ClientErrorController.oneLine("a\nb\r\nc\td", 100)).isEqualTo("a ↵ b ↵ c d");
        assertThat(ClientErrorController.oneLine(null, 10)).isEqualTo("-");
        assertThat(ClientErrorController.oneLine("x".repeat(20), 5)).hasSize(5);
    }
}
