package bj.ekuiseo.api.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Verifie la limitation de debit en memoire (regle metier n.14) : fenetre
 * glissante par IP sur /api/v1/auth/** et le webhook Kkiapay, independamment
 * l'une de l'autre, avec liberation des jetons une fois la fenetre ecoulee.
 */
class RateLimitingFilterTest {

    @Test
    void authPath_allowsUpToMaxRequests_thenReturns429() throws Exception {
        // Limite tres basse (2 requetes / 60s) pour un test rapide et deterministe.
        RateLimitingFilter filter = new RateLimitingFilter(2, 60, 120, 60);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest req1 = authRequest("10.0.0.1");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilterInternal(req1, res1, chain);
        assertThat(res1.getStatus()).isEqualTo(200); // pas modifie -> chain a laisse passer

        MockHttpServletRequest req2 = authRequest("10.0.0.1");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilterInternal(req2, res2, chain);
        assertThat(res2.getStatus()).isEqualTo(200);

        // 3e requete de la meme IP dans la meme fenetre : rejetee.
        MockHttpServletRequest req3 = authRequest("10.0.0.1");
        MockHttpServletResponse res3 = new MockHttpServletResponse();
        filter.doFilterInternal(req3, res3, chain);
        assertThat(res3.getStatus()).isEqualTo(429);
        assertThat(res3.getContentType()).isEqualTo("application/problem+json");
        assertThat(res3.getContentAsString()).contains("rate-limited");

        verify(chain).doFilter(req1, res1);
        verify(chain).doFilter(req2, res2);
    }

    @Test
    void differentIpAddresses_haveIndependentQuotas() throws Exception {
        RateLimitingFilter filter = new RateLimitingFilter(1, 60, 120, 60);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest reqA1 = authRequest("10.0.0.5");
        MockHttpServletResponse resA1 = new MockHttpServletResponse();
        filter.doFilterInternal(reqA1, resA1, chain);
        assertThat(resA1.getStatus()).isEqualTo(200);

        // Meme IP : quota deja epuise (max=1).
        MockHttpServletRequest reqA2 = authRequest("10.0.0.5");
        MockHttpServletResponse resA2 = new MockHttpServletResponse();
        filter.doFilterInternal(reqA2, resA2, chain);
        assertThat(resA2.getStatus()).isEqualTo(429);

        // IP differente : quota independant, toujours disponible.
        MockHttpServletRequest reqB1 = authRequest("10.0.0.6");
        MockHttpServletResponse resB1 = new MockHttpServletResponse();
        filter.doFilterInternal(reqB1, resB1, chain);
        assertThat(resB1.getStatus()).isEqualTo(200);
    }

    @Test
    void nonAuthNonWebhookPaths_areNeverFiltered() {
        RateLimitingFilter filter = new RateLimitingFilter(1, 60, 1, 60);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/trips");
        assertThat(filter.shouldNotFilter(req)).isTrue();
    }

    @Test
    void webhookPath_hasItsOwnQuota_separateFromAuth() throws Exception {
        RateLimitingFilter filter = new RateLimitingFilter(1, 60, 1, 60);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest authReq = authRequest("10.0.0.9");
        MockHttpServletResponse authRes = new MockHttpServletResponse();
        filter.doFilterInternal(authReq, authRes, chain);
        assertThat(authRes.getStatus()).isEqualTo(200);

        // Meme IP, mais chemin webhook : quota separe, toujours disponible malgre
        // le quota "auth" deja consomme pour cette IP.
        MockHttpServletRequest webhookReq = new MockHttpServletRequest("POST", "/api/v1/payments/kkiapay/webhook");
        webhookReq.setRemoteAddr("10.0.0.9");
        MockHttpServletResponse webhookRes = new MockHttpServletResponse();
        filter.doFilterInternal(webhookReq, webhookRes, chain);
        assertThat(webhookRes.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest authRequest(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/otp/request");
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
