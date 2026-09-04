package bj.ekuiseo.api.service.kkiapay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation de test/developpement de {@link KkiapayGateway}, active par defaut
 * (ekuiseo.kkiapay.mode=stub) quand aucune cle Kkiapay n'est configuree. Simule un
 * succes systematique afin de permettre de developper et de tester le reste du flux
 * de paiement (confirmation de reservation, notifications) sans compte marchand reel.
 *
 * <p>Ne JAMAIS activer en production : {@link KkiapayConfig} choisit cette implementation
 * uniquement quand ekuiseo.kkiapay.mode vaut "stub" (valeur par defaut), afin qu'un
 * demarrage en production sans configuration explicite echoue plutot que de "reussir"
 * silencieusement tous les paiements.</p>
 */
public class KkiapayStubGateway implements KkiapayGateway {

    private static final Logger log = LoggerFactory.getLogger(KkiapayStubGateway.class);

    public KkiapayStubGateway() {
        log.warn("KkiapayStubGateway actif : AUCUN paiement reel n'est effectue (ekuiseo.kkiapay.mode=stub). "
                + "A n'utiliser qu'en developpement/tests.");
    }

    @Override
    public VerificationResult verifyTransaction(String transactionId) {
        log.info("[KKIAPAY-STUB] verifyTransaction({}) -> SUCCESS (simule)", transactionId);
        return new VerificationResult(true, transactionId, 0L, 0L, "SUCCESS", null, null);
    }

    @Override
    public RefundResult refundTransaction(String transactionId) {
        log.info("[KKIAPAY-STUB] refundTransaction({}) -> succes simule", transactionId);
        return new RefundResult(true, "SIMULATED", "Remboursement simule (mode stub, aucun appel reel)");
    }
}
