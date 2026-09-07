package bj.ekuiseo.api.common;

/**
 * Arithmetique monetaire pure. Les montants sont exprimes en FCFA (XOF), toujours
 * en entiers (le franc CFA n'a pas de sous-unite usuelle et aucune piece
 * inferieure a 5 FCFA n'existe). Aucun parametre commercial ici (constat F015) :
 * le taux de commission et le palier d'arrondi vivent dans {@link FeePolicy}, seule
 * source des regles metier n.2 et n.3.
 */
public final class MoneyUtils {

    private MoneyUtils() {
    }

    /**
     * Frais de service pour un montant, un taux (numerateur/denominateur) et un palier
     * d'arrondi donnes : {@code montant x taux}, arrondi au palier superieur.
     *
     * <p>Exemple : 1 234 FCFA a 8/100 et palier 5 -&gt; 98,72 FCFA -&gt; 100 FCFA.</p>
     *
     * @param amountFcfa montant de la reservation, en FCFA (doit etre &gt;= 0)
     * @return les frais, en FCFA, toujours multiples du palier
     */
    public static long computeServiceFee(long amountFcfa, long rateNumerator, long rateDenominator, long roundingStep) {
        if (amountFcfa < 0) {
            throw new IllegalArgumentException("Le montant ne peut pas etre negatif");
        }
        if (rateDenominator <= 0 || roundingStep <= 0) {
            throw new IllegalArgumentException("Le denominateur du taux et le palier d'arrondi doivent etre positifs");
        }
        long numerator = amountFcfa * rateNumerator;
        long denom = rateDenominator * roundingStep;
        long units = ceilDiv(numerator, denom);
        return units * roundingStep;
    }

    /** Division entiere avec arrondi au superieur (a et b positifs ou nuls, b &gt; 0). */
    public static long ceilDiv(long a, long b) {
        if (b <= 0) {
            throw new IllegalArgumentException("Le diviseur doit etre positif");
        }
        if (a <= 0) {
            return 0L;
        }
        return (a + b - 1) / b;
    }

    /**
     * Arrondit {@code amountFcfa} au palier {@code step} superieur (utilise par
     * FeePolicy#computeDepositAmount, regle metier n.3). Un montant negatif ou
     * nul est arrondi a 0.
     */
    public static long roundUpToStep(long amountFcfa, long step) {
        if (step <= 0) {
            throw new IllegalArgumentException("Le palier d'arrondi doit etre positif");
        }
        if (amountFcfa <= 0) {
            return 0L;
        }
        return ceilDiv(amountFcfa, step) * step;
    }
}
