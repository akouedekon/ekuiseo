package bj.ekuiseo.api.dto.payment;

import java.util.Map;
import java.util.UUID;

/**
 * Ce que le frontend a besoin de recevoir pour ouvrir le widget Kkiapay
 * cote client (le paiement lui-meme est initie par ce widget, pas par ce
 * backend : voir {@link bj.ekuiseo.api.service.kkiapay.KkiapayGateway}).
 *
 * @param paymentId      identifiant local du paiement (etat INITIATED)
 * @param transactionRef reference de correlation interne (PAS l'identifiant de transaction Kkiapay,
 *                        qui n'existe qu'une fois le paiement effectue par le widget)
 * @param amount         montant a payer maintenant en ligne via Kkiapay, en FCFA -
 *                        pour une reservation, c'est {@code booking.depositAmount}
 *                        (la totalite en MOMO_FULL, un acompte en MOMO_DEPOSIT,
 *                        jamais {@code booking.amount} ; regle metier n.21), pour
 *                        un abonnement conducteur c'est son prix mensuel
 * @param kkiapayPublicKey cle publique a passer au widget
 * @param sandbox        true en environnement de test Kkiapay
 * @param widgetData     donnees personnalisees a transmettre telles quelles au parametre
 *                       "data" du widget Kkiapay, afin qu'elles soient echoees dans
 *                       {@code stateData} du webhook (voir KkiapayWebhookPayload) ; contient
 *                       {@code bookingId} ou {@code subscriptionId} selon le contexte.
 */
public record InitiatePaymentResponse(
        UUID paymentId,
        String transactionRef,
        long amount,
        String kkiapayPublicKey,
        boolean sandbox,
        Map<String, Object> widgetData
) {
}
