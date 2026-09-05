package bj.ekuiseo.api.domain.enums;

/**
 * Cycle de vie d un trajet.
 * <ul>
 *   <li>{@code DRAFT} : brouillon, visible du seul conducteur.</li>
 *   <li>{@code TEMPLATE} : modele d une navette quotidienne (recurrence) ; jamais
 *       cherchable ni reservable, il engendre des occurrences {@code PUBLISHED}.</li>
 *   <li>{@code PUBLISHED} / {@code FULL} : ouvert a la reservation / complet.</li>
 *   <li>{@code ONGOING} : depart passe (TripLifecycleScheduler), plus de reservation ni d annulation.</li>
 *   <li>{@code COMPLETED} : termine ; les reservations confirmees deviennent COMPLETED,
 *       la reservation devient reversable au conducteur.</li>
 *   <li>{@code CANCELLED} : annule par le conducteur, la moderation ou la suspension.</li>
 * </ul>
 */
public enum TripStatus {
    DRAFT,
    TEMPLATE,
    PUBLISHED,
    FULL,
    ONGOING,
    COMPLETED,
    CANCELLED
}
