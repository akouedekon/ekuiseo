package bj.ekuiseo.api.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Politique d'annulation d'une reservation par le passager (regle metier n.7,
 * amendee par la regle n.21) :
 * <ul>
 *   <li>Annulation plus de 24 h avant le depart : remboursement integral (gratuit).</li>
 *   <li>Annulation moins de 24 h avant le depart (mais avant l'heure de depart) :
 *       50 % du montant est retenu par la plateforme.</li>
 *   <li>Annulation apres l'heure de depart : 100 % est retenu (aucun remboursement).</li>
 * </ul>
 *
 * <p><b>Depuis la regle n.21 (paiement fractionne, migration V7)</b>, {@code amount}
 * doit etre {@code booking.depositAmount} - le seul montant que la plateforme a
 * reellement encaisse en ligne - jamais {@code booking.amount} : voir
 * BookingService#cancelByPassenger, seul appelant de cette methode.</p>
 */
@Component
public class CancellationPolicy {

    public static final Duration FREE_CANCELLATION_WINDOW = Duration.ofHours(24);

    public record Outcome(long refundAmount, long retainedAmount, String reason) {
    }

    /** @param amount montant effectivement encaisse en ligne (voir javadoc de la classe) */
    public Outcome evaluate(long amount, Instant now, Instant departureAt) {
        if (amount < 0) {
            throw new IllegalArgumentException("Le montant ne peut pas etre negatif");
        }
        if (now.isBefore(departureAt.minus(FREE_CANCELLATION_WINDOW))) {
            return new Outcome(amount, 0L, "Annulation gratuite (plus de 24h avant le depart)");
        }
        if (now.isBefore(departureAt)) {
            long retained = amount / 2;
            long refund = amount - retained;
            return new Outcome(refund, retained, "50% retenus (moins de 24h avant le depart)");
        }
        return new Outcome(0L, amount, "100% retenus (annulation apres l'heure de depart)");
    }
}
