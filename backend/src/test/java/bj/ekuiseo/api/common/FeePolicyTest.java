package bj.ekuiseo.api.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeePolicyTest {

    @ParameterizedTest(name = "amount={0}, rate={1}, step={2} -> fee attendu={3}")
    @CsvSource({
            "1234, 0.08, 5, 100",
            "1000, 0.08, 5, 80",
            "625, 0.08, 5, 50",
            "1234, 0.10, 10, 130", // 10% de 1234 = 123.4 -> arrondi au palier de 10 superieur = 130
            "0, 0.08, 5, 0"
    })
    void computeServiceFee_matchesConfiguredRateAndStep(long amount, double rate, long step, long expectedFee) {
        FeePolicy policy = new FeePolicy(rate, step, 1000);
        assertThat(policy.computeServiceFee(amount)).isEqualTo(expectedFee);
    }

    @Test
    void computeServiceFee_isZero_whenCommissionWaived() {
        // Regle metier n.11 : conducteur abonne -> commission ramenee a 0%, quel que soit le montant.
        FeePolicy policy = new FeePolicy(0.08, 5, 1000);
        assertThat(policy.computeServiceFee(100_000, true)).isZero();
        assertThat(policy.netDriverAmount(100_000, true)).isEqualTo(100_000);
    }

    @Test
    void netDriverAmount_isAmountMinusFee_whenNotWaived() {
        FeePolicy policy = new FeePolicy(0.08, 5, 1000);
        long amount = 2500;
        long fee = policy.computeServiceFee(amount);
        assertThat(policy.netDriverAmount(amount, false)).isEqualTo(amount - fee);
    }

    @Test
    void rejectsRateOutOfRange() {
        assertThatThrownBy(() -> new FeePolicy(1.0, 5, 1000)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FeePolicy(-0.01, 5, 1000)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNonPositiveRoundingStep() {
        assertThatThrownBy(() -> new FeePolicy(0.08, 0, 1000)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FeePolicy(0.08, -1, 1000)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNegativeDepositBase() {
        assertThatThrownBy(() -> new FeePolicy(0.08, 5, -1)).isInstanceOf(IllegalStateException.class);
    }

    /**
     * Regle metier n.21, point 2 (le "point delicat" du reversement) : sans le
     * max(acompte de base, frais de service), une reservation ou la commission
     * depasse l'acompte de base ferait encaisser a la plateforme, au moment de
     * l'acompte, moins que sa propre commission.
     */
    @Test
    void computeDepositAmount_isAtLeastServiceFee_evenWhenItExceedsTheBase() {
        FeePolicy policy = new FeePolicy(0.08, 5, 1000);
        long amount = 20_000; // 8% = 1600, > acompte de base (1000)
        long serviceFee = policy.computeServiceFee(amount); // 1600
        assertThat(policy.computeDepositAmount(amount, serviceFee)).isEqualTo(1600);
    }

    @Test
    void computeDepositAmount_usesBase_whenServiceFeeIsSmaller() {
        FeePolicy policy = new FeePolicy(0.08, 5, 1000);
        long amount = 5000; // 8% = 400, < acompte de base (1000)
        long serviceFee = policy.computeServiceFee(amount); // 400
        assertThat(policy.computeDepositAmount(amount, serviceFee)).isEqualTo(1000);
    }

    @Test
    void computeDepositAmount_neverExceedsTotalAmount() {
        FeePolicy policy = new FeePolicy(0.08, 5, 1000);
        long amount = 600; // < acompte de base (1000) : l'acompte ne peut pas depasser le total
        long serviceFee = policy.computeServiceFee(amount); // 48 -> arrondi a 50
        assertThat(policy.computeDepositAmount(amount, serviceFee)).isEqualTo(amount);
    }
}
