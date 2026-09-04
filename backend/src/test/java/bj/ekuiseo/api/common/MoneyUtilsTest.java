package bj.ekuiseo.api.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyUtilsTest {

    @ParameterizedTest(name = "amount={0} -> fee attendu={1}")
    @CsvSource({
            "0, 0",
            "50, 5",
            "100, 10",
            "625, 50",   // multiple exact de 5 : pas de sur-arrondi
            "1234, 100", // 8% = 98.72 -> arrondi a 100
            "1000, 80",  // 8% = 80 exact
            "1, 5",      // tres petit montant : minimum 5 FCFA
            "3000, 240"
    })
    void computeServiceFee_matchesExpectedRounding(long amount, long expectedFee) {
        assertThat(MoneyUtils.computeServiceFee(amount)).isEqualTo(expectedFee);
    }

    @Test
    void computeServiceFee_isAlwaysAMultipleOfFive() {
        for (long amount = 0; amount <= 10_000; amount += 37) {
            long fee = MoneyUtils.computeServiceFee(amount);
            assertThat(fee % 5).isZero();
        }
    }

    @Test
    void computeServiceFee_rejectsNegativeAmount() {
        assertThatThrownBy(() -> MoneyUtils.computeServiceFee(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void netDriverAmount_isAmountMinusFee() {
        long amount = 2500;
        long fee = MoneyUtils.computeServiceFee(amount);
        assertThat(MoneyUtils.netDriverAmount(amount)).isEqualTo(amount - fee);
    }

    @ParameterizedTest(name = "amount={0}, step={1} -> arrondi attendu={2}")
    @CsvSource({
            "1000, 5, 1000", // deja un multiple : inchange
            "1001, 5, 1005",
            "1600, 5, 1600",
            "0, 5, 0",
            "-10, 5, 0"      // negatif ou nul : arrondi a 0 (voir FeePolicy#computeDepositAmount)
    })
    void roundUpToStep_roundsUpToNextMultiple(long amount, long step, long expected) {
        assertThat(MoneyUtils.roundUpToStep(amount, step)).isEqualTo(expected);
    }

    @Test
    void roundUpToStep_rejectsNonPositiveStep() {
        assertThatThrownBy(() -> MoneyUtils.roundUpToStep(100, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyUtils.roundUpToStep(100, -5)).isInstanceOf(IllegalArgumentException.class);
    }
}
