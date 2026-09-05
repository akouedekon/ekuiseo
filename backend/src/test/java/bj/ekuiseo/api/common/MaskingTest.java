package bj.ekuiseo.api.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Constats F017/F511 : aucun code ni identifiant complet ne doit atteindre les journaux. */
class MaskingTest {

    @Test
    void masksEmailsAndPhones() {
        assertThat(Masking.email("lakouedekon@gmail.com")).isEqualTo("la***@gmail.com");
        assertThat(Masking.email("a@x.bj")).isEqualTo("a***@x.bj");
        assertThat(Masking.email("invalide")).isEqualTo("***");
        assertThat(Masking.email(null)).isEqualTo("***");
        assertThat(Masking.phone("+2290196870371")).isEqualTo("************71");
        assertThat(Masking.phone("12")).isEqualTo("***");
    }

    @Test
    void masksCodesInsideText() {
        assertThat(Masking.codes("Votre code Ekuiseo : 483920")).isEqualTo("Votre code Ekuiseo : ******");
        assertThat(Masking.codes("Ekuiseo : votre code de verification est 123456. Il expire dans 5 minutes."))
                .isEqualTo("Ekuiseo : votre code de verification est ******. Il expire dans 5 minutes.");
        assertThat(Masking.codes(null)).isNull();
    }
}
