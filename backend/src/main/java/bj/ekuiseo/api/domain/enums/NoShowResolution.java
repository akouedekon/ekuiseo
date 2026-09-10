package bj.ekuiseo.api.domain.enums;

/**
 * Issue d une declaration « conducteur absent » (V25).
 * <ul>
 *   <li>{@code REFUND_PASSENGER} : l acompte est rembourse integralement au passager, le
 *       conducteur ne touche rien pour cette place. Posee automatiquement a l echeance de
 *       la fenetre de contestation, ou par la moderation.</li>
 *   <li>{@code PAY_DRIVER} : la moderation retient que le trajet a eu lieu ; la reservation
 *       redevient {@code COMPLETED} et rejoint le prochain reversement du conducteur.</li>
 * </ul>
 */
public enum NoShowResolution {
    REFUND_PASSENGER,
    PAY_DRIVER
}
