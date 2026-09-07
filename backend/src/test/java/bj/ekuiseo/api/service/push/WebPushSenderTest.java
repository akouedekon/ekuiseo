package bj.ekuiseo.api.service.push;

import bj.ekuiseo.api.service.NotificationTemplates;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** V20 : cles absentes ou invalides = canal desactive sans exception ; contenu JSON minimal ; endpoint reduit a son origine dans les journaux. */
class WebPushSenderTest {

    @Test
    void withoutKeys_isDisabled_andSendReportsDisabled() {
        WebPushSender sender = new WebPushSender("", "", "mailto:contact@ekuiseo.com");
        assertThat(sender.isEnabled()).isFalse();
        assertThat(sender.publicKey()).isNull();
        assertThat(sender.send("https://push.example/x", "k", "a", new NotificationTemplates.Push("t", "b", "/", "tag")))
                .isEqualTo(WebPushSender.Outcome.DISABLED);
    }

    @Test
    void invalidKeys_doNotFailStartup() {
        assertThatCode(() -> {
            WebPushSender sender = new WebPushSender("pas-une-cle", "pas-une-cle", "mailto:contact@ekuiseo.com");
            assertThat(sender.isEnabled()).isFalse();
        }).doesNotThrowAnyException();
    }

    @Test
    void payloadIsASmallJson_andLogsHideTheDevicePath() {
        WebPushSender sender = new WebPushSender("", "", "mailto:contact@ekuiseo.com");
        String json = sender.toJson(new NotificationTemplates.Push("Nouveau message", "Consultez l'application.", "/messages", "new_message"));
        assertThat(json).isEqualTo("{\"title\":\"Nouveau message\",\"body\":\"Consultez l'application.\",\"url\":\"/messages\",\"tag\":\"new_message\"}");
        assertThat(WebPushSender.origin("https://fcm.googleapis.com/fcm/send/abc-secret")).isEqualTo("https://fcm.googleapis.com");
        assertThat(WebPushSender.origin(null)).isEqualTo("?");
    }
}
