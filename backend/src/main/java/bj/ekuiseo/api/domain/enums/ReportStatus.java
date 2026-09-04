package bj.ekuiseo.api.domain.enums;

/**
 * Etat de traitement d'un signalement (utilisateur ou trajet) par la moderation.
 *
 * <p>Valeurs alignees sur le contrat front (voir {@code frontend/src/api/extended.ts},
 * type {@code ReportStatus}) : {@code IN_REVIEW} et {@code RESOLVED} remplacent les
 * noms initiaux {@code REVIEWING}/{@code ACTION_TAKEN} (migration V6, aucune donnee
 * existante affectee : la fonctionnalite est encore neuve).</p>
 */
public enum ReportStatus {
    OPEN,
    IN_REVIEW,
    RESOLVED,
    DISMISSED
}
