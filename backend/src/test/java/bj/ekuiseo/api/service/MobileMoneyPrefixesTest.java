package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Constat F607 : coherence operateur / prefixe du numero (plan de numerotation 2024, prefixes configurables). */
class MobileMoneyPrefixesTest {

    private final MobileMoneyPrefixes prefixes = MobileMoneyPrefixes.defaults();

    @Test
    void detectsTheOperatorFromTheNationalPrefix() {
        assertThat(prefixes.detect("+2290197000322")).isEqualTo(MobileMoneyOperator.MTN_MOMO);
        assertThat(prefixes.detect("+2290155000001")).isEqualTo(MobileMoneyOperator.MOOV_MONEY);
        assertThat(prefixes.detect("+2290145000001")).isEqualTo(MobileMoneyOperator.CELTIIS_CASH);
        assertThat(prefixes.detect("+2290170000000")).isNull();
        assertThat(prefixes.detect("+22890000000")).isNull();
    }

    @Test
    void refusesAMismatchWithAnExplicitMessage() {
        assertThatThrownBy(() -> prefixes.assertConsistent(MobileMoneyOperator.MOOV_MONEY, "+2290197000322"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("MTN MoMo").hasMessageContaining("Moov Money");
        assertThatThrownBy(() -> prefixes.assertConsistent(MobileMoneyOperator.MTN_MOMO, "+2290170000000"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("aucun operateur");
    }

    @Test
    void acceptsMatchingAndForeignNumbers() {
        assertThatCode(() -> prefixes.assertConsistent(MobileMoneyOperator.MTN_MOMO, "+2290197000322")).doesNotThrowAnyException();
        // Un numero etranger (Togo) n est pas controle : plan de numerotation inconnu.
        assertThatCode(() -> prefixes.assertConsistent(MobileMoneyOperator.MOOV_MONEY, "+22890000000")).doesNotThrowAnyException();
        assertThatCode(() -> prefixes.assertConsistent(null, "+2290197000322")).doesNotThrowAnyException();
    }

    @Test
    void prefixesAreConfigurable() {
        MobileMoneyPrefixes custom = new MobileMoneyPrefixes("0170, 0171", "0155", "0140");
        assertThat(custom.detect("+2290170000000")).isEqualTo(MobileMoneyOperator.MTN_MOMO);
        assertThat(custom.detect("+2290197000322")).isNull();
    }
}
