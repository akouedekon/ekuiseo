package bj.ekuiseo.api.dto.monitoring;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Rapport d erreur envoye par le navigateur (frontend/src/lib/monitoring.ts, constat F440) :
 * message, pile tronquee, origine (window.onerror, unhandledrejection, componentDidCatch,
 * query:...), route sans parametres, version du paquet et agent utilisateur. Aucune donnee
 * personnelle n y figure par construction ; les bornes ci-dessous empechent qu un client
 * malveillant n y glisse des kilo-octets.
 */
public record ClientErrorReport(
        @NotBlank @Size(max = 1000) String message,
        @Size(max = 4000) String stack,
        @NotBlank @Size(max = 100) String source,
        @Size(max = 300) String route,
        @Size(max = 50) String version,
        @Size(max = 400) String userAgent,
        @Size(max = 40) String occurredAt,
        @Size(max = 4000) String componentStack,
        Integer status
) {
}
