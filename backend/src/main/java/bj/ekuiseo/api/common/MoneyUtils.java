package bj.ekuiseo.api.common;

/**
 * Utilitaires monetaires. Les montants sont exprimes en FCFA (XOF), toujours
 * en entiers (le franc CFA n'a pas de sous-unite usuelle et aucune piece
 * inferieure a 5 FCFA n'existe).
 */
public final class MoneyUtils {

    /** Taux de commission de la plateforme : 8 %. */
    public static final int SERVICE_FEE_RATE_NUMERATOR = 8;
    public static final int SERVICE_FEE_RATE_DENOMINATOR = 100;

    /** Palier d'arrondi : les FCFA n'existent pas en dessous de 5. */
    public static final long ROUNDING_STEP = 5L;

    private MoneyUtils() {
    }

    /**
     * Calcule les frais de service de la plateforme (regle metier n.4) :
     * 8 % du montant, arrondis aux 5 FCFA superieurs.
     *
     * <p>Exemple : 1 234 FCFA -&gt; 8 % = 98,72 FCFA -&gt; arrondi a 100 FCFA.</p>
     *
     * @param amountFcfa montant de la reservation, en FCFA (doit etre &gt;= 0)
     * @return les frais de service, en FCFA, toujours multiples de 5
     */
    public static long computeServiceFee(long amountFcfa) {
        return computeServiceFee(amountFcfa, SERVICE_FEE_RATE_NUMERATOR, SERVICE_FEE_RATE_DENOMINATOR, ROUNDING_STEP);
    }

    /**
     * Variante generique (taux et palier d'arrondi parametrables) utilisee pour
     * les tests et une eventuelle evolution tarifaire.
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

    /** Montant net revenant au conducteur = montant total - frais de service. */
    public static long netDriverAmount(long amountFcfa) {
        return amountFcfa - computeServiceFee(amountFcfa);
    }

    /**
     * Arrondit {@code amountFcfa} au palier {@code step} superieur (utilise par
     * FeePolicy#computeDepositAmount, regle metier n.21). Un montant negatif ou
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
