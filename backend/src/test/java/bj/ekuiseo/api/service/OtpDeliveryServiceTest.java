package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import bj.ekuiseo.api.dto.auth.OtpRequestResponse;
import bj.ekuiseo.api.service.mail.MailGateway;
import bj.ekuiseo.api.service.sms.SmsGateway;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Choix du canal (e-mail d'abord, SMS en repli), masquage de la destination et limite de debit. */
class OtpDeliveryServiceTest {

    private final List<String[]> mails = new ArrayList<>();
    private final List<String[]> sms = new ArrayList<>();
    private final MailGateway mailGateway = (to, subject, text) -> mails.add(new String[] {to, subject, text});
    private final SmsGateway smsGateway = (to, text) -> sms.add(new String[] {to, text});
    private final SmsService smsService = new SmsService(smsGateway);

    private OtpDeliveryService service(String channel, boolean fallback) {
        return new OtpDeliveryService(mailGateway, smsService, new OtpRateLimiter(3, 10), channel, fallback);
    }

    @Test
    void envoieParEmailQuandLeCompteAUneAdresse() {
        OtpRequestResponse res = service("email", false).deliver("+22997000321", "afi.testeur@example.com", "123456");

        assertThat(res.channel()).isEqualTo("EMAIL");
        assertThat(res.destination()).isEqualTo("af***@example.com");
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0)[0]).isEqualTo("afi.testeur@example.com");
        assertThat(mails.get(0)[1]).contains("123456");
        assertThat(mails.get(0)[2]).contains("123456").contains("5 minutes");
        assertThat(sms).isEmpty();
    }

    @Test
    void refuseUnCompteSansEmailQuandLeRepliSmsEstDesactive() {
        assertThatThrownBy(() -> service("email", false).deliver("+22997000321", null, "123456"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Aucune adresse e-mail");
        assertThat(mails).isEmpty();
        assertThat(sms).isEmpty();
    }

    @Test
    void retombeSurLeSmsQuandLeCompteSansEmailEtRepliActif() {
        OtpRequestResponse res = service("email", true).deliver("+22997000321", "  ", "123456");

        assertThat(res.channel()).isEqualTo("SMS");
        assertThat(res.destination()).isEqualTo("**********21");
        assertThat(sms).hasSize(1);
        assertThat(sms.get(0)[1]).contains("123456");
        assertThat(mails).isEmpty();
    }

    @Test
    void canalSmsForceIgnoreLAdresseEmail() {
        OtpRequestResponse res = service("sms", false).deliver("+22997000321", "afi.testeur@example.com", "123456");

        assertThat(res.channel()).isEqualTo("SMS");
        assertThat(sms).hasSize(1);
        assertThat(mails).isEmpty();
    }

    @Test
    void limiteLeDebitParNumeroQuelQueSoitLeCanal() {
        OtpDeliveryService svc = service("email", true);
        svc.deliver("+22997000321", "a@example.com", "1");
        svc.deliver("+22997000321", null, "2");
        svc.deliver("+22997000321", "a@example.com", "3");

        assertThatThrownBy(() -> svc.deliver("+22997000321", "a@example.com", "4"))
                .isInstanceOf(TooManyRequestsException.class);
        // Un autre numero n'est pas affecte.
        svc.deliver("+22997000322", "b@example.com", "5");
        assertThat(mails).hasSize(3);
        assertThat(sms).hasSize(1);
    }

    @Test
    void masqueLesDestinations() {
        assertThat(OtpDeliveryService.maskEmail("a@gmail.com")).isEqualTo("a***@gmail.com");
        assertThat(OtpDeliveryService.maskEmail("lakouedekon@gmail.com")).isEqualTo("la***@gmail.com");
        assertThat(OtpDeliveryService.maskEmail("sans-arobase")).isEqualTo("***");
        assertThat(OtpDeliveryService.maskPhone("+2290196870371")).isEqualTo("************71");
        assertThat(OtpDeliveryService.maskPhone(null)).isEqualTo("***");
    }
}
