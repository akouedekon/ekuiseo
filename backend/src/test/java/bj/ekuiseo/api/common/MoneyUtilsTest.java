package bj.ekuiseo.api.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Arithmetique pure : le taux de 8 % et le palier de 5 F sont testes sur FeePolicy (seule source des regles metier). */
class MoneyUtilsTest {

    @ParameterizedTest(name = "amount={0}, rate={1}/{2}, step={3} -> fee attendu={4}")
    @CsvSource({
            "0, 8, 100, 5, 0",
            "50, 8, 100, 5, 5",
            "625, 8, 100, 5, 50",    // multiple exact de 5 : pas de sur-arrondi
            "1234, 8, 100, 5, 100",  // 8% = 98.72 -> arrondi a 100
            "1, 8, 100, 5, 5",       // tres petit montant : minimum un palier
            "1234, 10, 100, 10, 130" // 10% = 123.4 -> palier de 10 superieur
    })
    void computeServiceFee_roundsUpToTheStep(long amount, long num, long den, long step, long expectedFee) {
        assertThat(MoneyUtils.computeServiceFee(amount, num, den, step)).isEqualTo(expectedFee);
    }

    @Test
    void computeServiceFee_isAlwaysAMultipleOfTheStep() {
        for (long amount = 0; amount <= 10_000; amount += 37) {
            assertThat(MoneyUtils.computeServiceFee(amount, 8, 100, 5) % 5).isZero();
        }
    }

    @Test
    void computeServiceFee_rejectsNegativeAmount_andNonPositiveDivisors() {
        assertThatThrownBy(() -> MoneyUtils.computeServiceFee(-1, 8, 100, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyUtils.computeServiceFee(100, 8, 0, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyUtils.computeServiceFee(100, 8, 100, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ceilDiv_roundsUp() {
        assertThat(MoneyUtils.ceilDiv(10, 5)).isEqualTo(2);
        assertThat(MoneyUtils.ceilDiv(11, 5)).isEqualTo(3);
        assertThat(MoneyUtils.ceilDiv(0, 5)).isZero();
        assertThatThrownBy(() -> MoneyUtils.ceilDiv(1, 0)).isInstanceOf(IllegalArgumentException.class);
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
