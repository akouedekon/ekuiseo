package bj.ekuiseo.api.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Constats F017/F511/F512 : aucun code ni identifiant complet ne doit atteindre les journaux ni le client. */
class MaskingTest {

    @Test
    void masksEmailsAndPhones() {
        // Un seul caractere de la partie locale, domaine tronque a sa premiere lettre et son extension.
        assertThat(Masking.email("lakouedekon@gmail.com")).isEqualTo("l***@g***.com");
        assertThat(Masking.email("a@x.bj")).isEqualTo("a***@x***.bj");
        assertThat(Masking.email("afi.testeur@example.com")).isEqualTo("a***@e***.com");
        assertThat(Masking.email("koffi@localhost")).isEqualTo("k***@l***");
        assertThat(Masking.email("invalide")).isEqualTo("***");
        assertThat(Masking.email("vide@")).isEqualTo("***");
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
