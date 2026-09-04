package bj.ekuiseo.api.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Politique de commission de la plateforme, parametrable depuis application.yml
 * (ekuiseo.fee.service-fee-rate, ekuiseo.fee.rounding-step) au lieu d'etre codee
 * en dur dans {@link MoneyUtils} : ces deux valeurs sont un parametre commercial
 * qui doit pouvoir evoluer sans recompilation.
 *
 * <p>Le taux est exprime en application.yml comme une fraction decimale (ex:
 * 0.08 pour 8%). Pour rester en arithmetique entiere exacte (voir MoneyUtils),
 * on le convertit une fois pour toutes en une fraction numerateur/denominateur
 * sur 10 000 (precision au 1/100e de %), ce qui couvre tous les taux commerciaux
 * raisonnables (ex: 0.08 -> 800/10000, 0.075 -> 750/10000).</p>
 */
@Component
public class FeePolicy {

    private static final long RATE_DENOMINATOR = 10_000L;

    private final long rateNumerator;
    private final long roundingStep;
    private final long depositBaseFcfa;

    public FeePolicy(@Value("${ekuiseo.fee.service-fee-rate}") double serviceFeeRate,
                      @Value("${ekuiseo.fee.rounding-step}") long roundingStep,
                      @Value("${ekuiseo.booking.deposit-base-fcfa:1000}") long depositBaseFcfa) {
        if (serviceFeeRate < 0 || serviceFeeRate >= 1) {
            throw new IllegalStateException(
                    "ekuiseo.fee.service-fee-rate doit etre compris entre 0 (inclus) et 1 (exclu), recu : " + serviceFeeRate);
        }
        if (roundingStep <= 0) {
            throw new IllegalStateException("ekuiseo.fee.rounding-step doit etre strictement positif, recu : " + roundingStep);
        }
        if (depositBaseFcfa < 0) {
            throw new IllegalStateException(
                    "ekuiseo.booking.deposit-base-fcfa ne peut pas etre negatif, recu : " + depositBaseFcfa);
        }
        this.rateNumerator = Math.round(serviceFeeRate * RATE_DENOMINATOR);
        this.roundingStep = roundingStep;
        this.depositBaseFcfa = depositBaseFcfa;
    }

    /**
     * Frais de service pour un montant donne. Si {@code commissionWaived} est vrai
     * (conducteur abonne, regle metier n.11), la commission est ramenee a 0.
     */
    public long computeServiceFee(long amountFcfa, boolean commissionWaived) {
        if (commissionWaived) {
            return 0L;
        }
        return MoneyUtils.computeServiceFee(amountFcfa, rateNumerator, RATE_DENOMINATOR, roundingStep);
    }

    public long computeServiceFee(long amountFcfa) {
        return computeServiceFee(amountFcfa, false);
    }

    public long netDriverAmount(long amountFcfa, boolean commissionWaived) {
        return amountFcfa - computeServiceFee(amountFcfa, commissionWaived);
    }

    /**
     * Montant de l'acompte mobile money d'une reservation en mode MOMO_DEPOSIT
     * (regle metier n.21) : {@code max(acompte de base configure, frais de
     * service de cette reservation)}, arrondi au palier {@code
     * ekuiseo.fee.rounding-step} superieur, plafonne au montant total de la
     * reservation.
     *
     * <p>Le {@code max} avec les frais de service n'est pas cosmetique : sans lui,
     * une reservation dont le taux de commission configure depasserait l'acompte
     * de base ferait encaisser a la plateforme, au moment de l'acompte, moins que
     * sa propre commission - elle devrait alors de l'argent au conducteur lors du
     * reversement (voir PayoutService#netAmount, qui suppose deposit &gt;=
     * serviceFee - c'est cette methode qui garantit l'invariant).</p>
     */
    public long computeDepositAmount(long amountFcfa, long serviceFeeFcfa) {
        if (amountFcfa < 0 || serviceFeeFcfa < 0) {
            throw new IllegalArgumentException("Les montants ne peuvent pas etre negatifs");
        }
        long raw = Math.max(depositBaseFcfa, serviceFeeFcfa);
        long rounded = MoneyUtils.roundUpToStep(raw, roundingStep);
        return Math.min(rounded, amountFcfa);
    }
}
