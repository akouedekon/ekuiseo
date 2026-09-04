package bj.ekuiseo.api.service.kkiapay;

/**
 * Abstraction de l'agregateur de paiement Kkiapay (mobile money : MTN MoMo,
 * Moov Money, Celtiis Cash ; carte bancaire ; Wave), isolant le reste de
 * l'application du format exact de l'API HTTP sous-jacente.
 *
 * <p><b>Ce qui est confirme</b> (source : documentation publique Kkiapay et code
 * source du SDK officiel {@code @kkiapay-org/nodejs-sdk}, consultes en ligne
 * lors de l'ecriture de ce fichier) :
 * <ul>
 *   <li>Base URL sandbox : {@code https://api-sandbox.kkiapay.me} ; production :
 *       {@code https://api.kkiapay.me}.</li>
 *   <li>Verification d'une transaction : {@code POST /api/v1/transactions/status}
 *       avec le corps {@code {"transactionId": "..."}}.</li>
 *   <li>Remboursement d'une transaction reussie : {@code POST /api/v1/transactions/revert}
 *       avec le corps {@code {"transactionId": "..."}} (les frais Kkiapay ne sont pas
 *       rembourses, seul le montant net l'est).</li>
 *   <li>Headers d'authentification sur ces deux routes : {@code x-api-key} (cle publique),
 *       {@code x-private-key} (cle privee), {@code x-secret-key} (cle secrete).</li>
 *   <li>La reponse de verification contient au moins : {@code transactionId}, {@code status}
 *       ("SUCCESS"/...), {@code amount}, {@code fees}, {@code failureCode}, {@code failureMessage}.</li>
 *   <li>Le paiement lui-meme n'est PAS initie par le serveur : le widget Javascript cote
 *       frontend (cle publique) ouvre la transaction directement aupres de Kkiapay ; le
 *       role du backend est de fournir la cle publique et une reference de correlation
 *       (voir InitiatePaymentResponse), puis de verifier/rembourser apres coup.</li>
 *   <li>Le webhook envoie un evenement {@code transaction.success}/{@code transaction.failed}
 *       avec un champ booleen {@code isPaymentSucces} (orthographe exacte de l'API), et porte
 *       une signature dans l'en-tete {@code x-kkiapay-secret} qui est directement le "secret
 *       hash" configure dans le tableau de bord Kkiapay (comparaison a effectuer en temps
 *       constant, PAS un HMAC calcule sur le corps).</li>
 * </ul>
 * </p>
 *
 * <p><b>Ce qui N'A PAS pu etre confirme depuis cet environnement</b> (aucune route
 * publique de reference REST n'a ete trouvee au-dela du code source minifie du SDK
 * Node.js ; a valider imperativement contre le compte marchand reel avant mise en
 * production) :
 * <ul>
 *   <li>La structure exacte de la reponse d'erreur (codes HTTP non-2xx) pour verify/refund.</li>
 *   <li>Si {@code refund} renvoie un statut immediat ou un traitement asynchrone (auquel cas
 *       un second webhook ou une seconde verification serait necessaire pour confirmer que le
 *       remboursement a bien abouti).</li>
 *   <li>Le format precis du corps de la reponse de remboursement (aucun exemple public trouve).</li>
 * </ul>
 * Ces zones d'incertitude sont volontairement isolees derriere cette interface : en cas
 * d'ecart avec la documentation Kkiapay officielle (a laquelle cet environnement de
 * developpement n'a pas eu acces complet), seule {@link KkiapayHttpGateway} doit etre
 * modifiee.</p>
 */
public interface KkiapayGateway {

    /** Interroge Kkiapay pour l'etat reel d'une transaction (jamais se fier au seul webhook). */
    VerificationResult verifyTransaction(String transactionId);

    /** Demande le remboursement d'une transaction reussie (les frais Kkiapay ne sont pas rembourses). */
    RefundResult refundTransaction(String transactionId);

    record VerificationResult(boolean success, String transactionId, long amountFcfa, long feesFcfa,
                               String rawStatus, String failureCode, String failureMessage) {
    }

    record RefundResult(boolean success, String rawStatus, String message) {
    }
}
