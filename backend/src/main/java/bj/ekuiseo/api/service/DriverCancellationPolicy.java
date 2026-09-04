package bj.ekuiseo.api.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Determine si l'annulation d'un trajet par son conducteur est "tardive" pour les
 * besoins des statistiques conducteur (regle metier n.9). Contrairement a
 * {@link CancellationPolicy} (remboursement du passager, qui reste toujours integral
 * quand c'est le conducteur qui annule : ce n'est jamais la faute du passager), cette
 * politique ne determine qu'une penalite statistique.
 *
 * <p><b>Seuil assume</b> : aucun bareme officiel n'a ete fourni pour l'annulation
 * conducteur. Par coherence avec la fenetre de gratuite passager
 * ({@link CancellationPolicy#FREE_CANCELLATION_WINDOW}, 24h), une annulation conducteur
 * est consideree tardive si elle intervient a moins de 24h du depart ET que le trajet
 * avait au moins une reservation active. A ajuster si le commanditaire fournit un
 * bareme different.</p>
 */
@Component
public class DriverCancellationPolicy {

    public static final Duration LATE_WINDOW = Duration.ofHours(24);

    public boolean isLate(Instant now, Instant departureAt) {
        return now.isAfter(departureAt.minus(LATE_WINDOW));
    }
}
