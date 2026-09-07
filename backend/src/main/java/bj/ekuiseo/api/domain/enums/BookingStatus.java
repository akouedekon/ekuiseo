package bj.ekuiseo.api.domain.enums;

/**
 * Cycle d une reservation.
 * <ul>
 *   <li>{@code PENDING_PAYMENT} : acompte attendu (20 minutes, regle metier n.6).</li>
 *   <li>{@code PENDING_DRIVER_APPROVAL} : acompte encaisse (ou especes) sur un trajet dont le
 *       conducteur accepte chaque passager ({@code trips.instant_booking = false}, V19) ; places
 *       decrementees, reponse attendue avant {@code approval_deadline_at}.</li>
 *   <li>{@code CONFIRMED} : acompte encaisse (ou especes), et accord du conducteur si le trajet l exige.</li>
 *   <li>{@code CANCELLED_BY_PASSENGER} / {@code CANCELLED_BY_DRIVER} : annulation volontaire.</li>
 *   <li>{@code EXPIRED} : acompte jamais recu dans le delai, places liberees par le
 *       scheduler (V16, constats F010/F116/F232) - distinct d une annulation volontaire.</li>
 *   <li>{@code COMPLETED} / {@code NO_SHOW} : trajet effectue / passager absent.</li>
 * </ul>
 */
public enum BookingStatus {
    PENDING_PAYMENT,
    PENDING_DRIVER_APPROVAL,
    CONFIRMED,
    CANCELLED_BY_PASSENGER,
    CANCELLED_BY_DRIVER,
    COMPLETED,
    NO_SHOW,
    EXPIRED
}
