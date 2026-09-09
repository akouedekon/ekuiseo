package bj.ekuiseo.api.domain.enums;

/**
 * Constat du passager apres l heure de depart (V21).
 * <ul>
 *   <li>{@code PENDING} : rien declare ; vaut confirmation tacite passe le delai d eligibilite
 *       au reversement (24 h apres le depart).</li>
 *   <li>{@code TRIP_DONE} : le passager confirme que le trajet a eu lieu.</li>
 *   <li>{@code DRIVER_NO_SHOW} : le passager declare que le conducteur n est pas venu ; la
 *       reservation passe {@code BookingStatus.DRIVER_NO_SHOW} et un signalement est ouvert.</li>
 * </ul>
 */
public enum PassengerConfirmation {
    PENDING,
    TRIP_DONE,
    DRIVER_NO_SHOW
}
