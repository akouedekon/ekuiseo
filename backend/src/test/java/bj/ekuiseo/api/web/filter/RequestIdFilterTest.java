package bj.ekuiseo.api.web.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Correlation des journaux (constat F439) : identifiant repris ou genere, expose en reponse, MDC vide en sortie. */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void safeIncomingId_isKept_exposedInResponse_andPresentInMdcDuringTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/trips/search");
        request.addHeader(RequestIdFilter.HEADER, "caddy-0123456789abcdef");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenInMdc.set(MDC.get(RequestIdFilter.MDC_REQUEST_ID));

        filter.doFilterInternal(request, response, chain);

        assertThat(seenInMdc.get()).isEqualTo("caddy-0123456789abcdef");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("caddy-0123456789abcdef");
        assertThat(MDC.get(RequestIdFilter.MDC_REQUEST_ID)).isNull();
    }

    @Test
    void unsafeOrMissingIncomingId_isReplacedByAGeneratedOne() throws Exception {
        assertThat(RequestIdFilter.resolveRequestId(null)).matches("[0-9a-f]{16}");
        assertThat(RequestIdFilter.resolveRequestId("court")).matches("[0-9a-f]{16}");
        assertThat(RequestIdFilter.resolveRequestId("<script>alert(1)</script>")).matches("[0-9a-f]{16}");
        assertThat(RequestIdFilter.resolveRequestId("x".repeat(65))).matches("[0-9a-f]{16}");
        assertThat(RequestIdFilter.resolveRequestId("  abc-123_XYZ.9  ")).isEqualTo("abc-123_XYZ.9");
    }

    @Test
    void mdcIsCleared_evenWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/bookings");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            MDC.put(RequestIdFilter.MDC_USER_ID, "u-1");
            throw new IllegalStateException("boom");
        };

        try {
            filter.doFilterInternal(request, response, chain);
        } catch (Exception expected) {
            // propagee telle quelle : le handler d erreurs de Spring s en charge plus haut
        }

        assertThat(MDC.get(RequestIdFilter.MDC_REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestIdFilter.MDC_USER_ID)).isNull();
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotNull();
    }
}
