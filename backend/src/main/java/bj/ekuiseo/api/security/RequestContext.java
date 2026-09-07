package bj.ekuiseo.api.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Adresse IP et User-Agent de la requete HTTP en cours, pour le journal d audit des
 * evenements d authentification (constat F541). Lit le contexte de requete de Spring :
 * hors requete (tache planifiee, test unitaire), les deux valeurs sont nulles et rien
 * n echoue. L adresse suit la meme logique que {@link RateLimitingFilter#clientIp}
 * (X-Real-IP, sinon dernier element de X-Forwarded-For, sinon adresse socket).
 */
@Component
public class RequestContext {

    private static final int USER_AGENT_MAX_LENGTH = 200;

    /** Adresse du client, ou null hors requete HTTP. */
    public String clientIp() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : RateLimitingFilter.clientIp(request);
    }

    /** User-Agent tronque a 200 caracteres, ou null hors requete HTTP ou sans en-tete. */
    public String userAgent() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String ua = request.getHeader("User-Agent");
        if (ua == null || ua.isBlank()) {
            return null;
        }
        return ua.length() <= USER_AGENT_MAX_LENGTH ? ua : ua.substring(0, USER_AGENT_MAX_LENGTH);
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
