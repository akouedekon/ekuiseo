package bj.ekuiseo.api.service.payment;

/**
 * Fournisseur de paiement (contrat A.11) : ce que les services metier (PaymentService,
 * RefundService, ReconciliationService) attendent d un agregateur, sans nommer Kkiapay.
 * {@link bj.ekuiseo.api.service.kkiapay.KkiapayGateway} en est l implementation actuelle ;
 * un autre agregateur se branche en implementant cette interface et en changeant
 * {@code ekuiseo.kkiapay.mode} pour une configuration equivalente.
 */
public interface PaymentProvider {

    /** Nom court du fournisseur, tel qu il apparait dans payments.provider et le registre (« KKIAPAY »). */
    String name();

    /** Interroge le fournisseur pour l etat reel d une transaction (jamais se fier au seul webhook). */
    VerificationResult verifyTransaction(String transactionId);

    /** Demande le remboursement d une transaction reussie (les frais du fournisseur ne sont pas rembourses). */
    RefundResult refundTransaction(String transactionId);

    /**
     * Verdict du fournisseur sur une transaction. {@code operator} est l operateur reel tel que
     * renvoye par l API de verification ("MTN", "MOOV", "CELTIIS", "CARD"...), ou null si l API
     * ne le donne pas ; c est lui, et non la declaration du widget, qui alimente
     * {@code payments.channel}. {@code feesFcfa} : frais preleves par le fournisseur, portes au
     * registre (PROVIDER_FEE).
     */
    record VerificationResult(boolean success, String transactionId, long amountFcfa, long feesFcfa,
                              String rawStatus, String failureCode, String failureMessage, String operator) {

        /** Variante sans operateur (tests, API n exposant pas la source). */
        public VerificationResult(boolean success, String transactionId, long amountFcfa, long feesFcfa,
                                  String rawStatus, String failureCode, String failureMessage) {
            this(success, transactionId, amountFcfa, feesFcfa, rawStatus, failureCode, failureMessage, null);
        }

        /** Vrai quand le fournisseur ne connait pas la transaction (404, code d erreur explicite). */
        public boolean unknownTransaction() {
            String raw = rawStatus == null ? "" : rawStatus.toUpperCase();
            String code = failureCode == null ? "" : failureCode.toUpperCase();
            return raw.equals("HTTP_404") || raw.contains("NOT_FOUND") || code.contains("NOT_FOUND")
                    || code.contains("UNKNOWN") || raw.contains("UNKNOWN");
        }
    }

    record RefundResult(boolean success, String rawStatus, String message) {
    }
}
