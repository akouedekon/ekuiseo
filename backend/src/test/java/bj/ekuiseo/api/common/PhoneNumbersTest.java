package bj.ekuiseo.api.common;

import bj.ekuiseo.api.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Constat F410 : une seule normalisation E.164, plan beninois a 10 chiffres. */
class PhoneNumbersTest {

    @Test
    void normalizesBeninNumbers() {
        assertThat(PhoneNumbers.normalize("+229 01 97 00 03 22")).isEqualTo("+2290197000322");
        assertThat(PhoneNumbers.normalize("0197000322")).isEqualTo("+2290197000322");
        assertThat(PhoneNumbers.normalize("01 97 00 03 22")).isEqualTo("+2290197000322");
        assertThat(PhoneNumbers.normalize("00229 0197000322")).isEqualTo("+2290197000322");
        assertThat(PhoneNumbers.normalize("+229-01-96-87-03-71")).isEqualTo("+2290196870371");
    }

    @Test
    void acceptsOtherCountriesInE164() {
        assertThat(PhoneNumbers.normalize("+228 90 00 00 00")).isEqualTo("+22890000000");
        assertThat(PhoneNumbers.normalize("+234 803 000 0000")).isEqualTo("+2348030000000");
    }

    @Test
    void rejectsLegacyEightDigitBeninNumbers() {
        assertThatThrownBy(() -> PhoneNumbers.normalize("+22997000322"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("10 chiffres");
        assertThatThrownBy(() -> PhoneNumbers.normalize("97000322"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("10 chiffres");
        assertThatThrownBy(() -> PhoneNumbers.normalize("+229 02 97 00 03 22"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("+229 01");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> PhoneNumbers.normalize("")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> PhoneNumbers.normalize("abc")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> PhoneNumbers.normalize("123456789")).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("indicatif");
        assertThatThrownBy(() -> PhoneNumbers.normalize("+1")).isInstanceOf(BadRequestException.class);
        assertThat(PhoneNumbers.isValid("+2290197000322")).isTrue();
        assertThat(PhoneNumbers.isValid("+22997000322")).isFalse();
    }
}
