package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.UserPreferences;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.domain.PushSubscription;
import bj.ekuiseo.api.repository.PushSubscriptionRepository;
import bj.ekuiseo.api.repository.UserPreferencesRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.service.mail.MailDeliveryException;
import bj.ekuiseo.api.service.mail.MailGateway;
import bj.ekuiseo.api.service.push.WebPushSender;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Canaux sortants (constat F107) : preferences respectees, e-mail verifie exige, SMS pour le critique seul, echec avale. */
class NotificationDispatcherTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserPreferencesRepository preferencesRepository = mock(UserPreferencesRepository.class);
    private final MailGateway mailGateway = mock(MailGateway.class);
    private final SmsService smsService = mock(SmsService.class);
    private final PushSubscriptionRepository pushSubscriptionRepository = mock(PushSubscriptionRepository.class);
    private final WebPushSender webPushSender = mock(WebPushSender.class);
    private final NotificationDispatcher dispatcher =
            new NotificationDispatcher(userRepository, preferencesRepository, mailGateway, smsService,
                    pushSubscriptionRepository, webPushSender);

    private final User user = User.builder().id(UUID.randomUUID()).firstName("Awa").phone("+2290100000000")
            .email("awa@example.bj").emailVerified(true).status(UserStatus.ACTIVE).build();

    @BeforeEach
    void setUp() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
    }

    @Test
    void email_sentWhenPreferenceOn_withReceiptDetails() {
        when(preferencesRepository.findByUserId(user.getId()))
                .thenReturn(Optional.of(UserPreferences.builder().notifyByEmail(true).build()));

        dispatcher.deliver(user.getId(), NotificationType.PAYMENT_SUCCEEDED, Map.of(
                "amountFcfa", 1000L, "totalFcfa", 4500L, "balanceDueOnBoardFcfa", 3500L, "reference", "KK-42",
                "route", "Cotonou -> Bohicon", "departureAt", "2026-09-10T07:00:00Z"), true, null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailGateway).send(eq("awa@example.bj"), anyString(), body.capture());
        assertThat(body.getValue()).contains("1 000 FCFA").contains("3 500 FCFA").contains("KK-42").contains("Cotonou -> Bohicon");
        // Critique + SMS autorise par defaut : le texte du gabarit part.
        verify(smsService).sendCritical(eq("+2290100000000"), anyString());
    }

    @Test
    void email_sentByDefault_andCriticalEvenIfOptedOut_neverWhenUnverified() {
        // Preference absente : e-mail actif par defaut (opt-out, V18).
        when(preferencesRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        dispatcher.deliver(user.getId(), NotificationType.NEW_MESSAGE, Map.of(), false, null);
        verify(mailGateway, org.mockito.Mockito.times(1)).send(any(), any(), any());

        // Preference desactivee : plus de message ordinaire, mais une notification critique
        // part quand meme (execution du contrat, seul canal sortant).
        when(preferencesRepository.findByUserId(user.getId()))
                .thenReturn(Optional.of(UserPreferences.builder().notifyByEmail(false).build()));
        dispatcher.deliver(user.getId(), NotificationType.NEW_MESSAGE, Map.of(), false, null);
        verify(mailGateway, org.mockito.Mockito.times(1)).send(any(), any(), any());
        dispatcher.deliver(user.getId(), NotificationType.BOOKING_CANCELLED, Map.of(), true, "Ekuiseo : annule");
        verify(mailGateway, org.mockito.Mockito.times(2)).send(any(), any(), any());

        org.mockito.Mockito.clearInvocations(mailGateway);
        user.setEmailVerified(false);
        when(preferencesRepository.findByUserId(user.getId()))
                .thenReturn(Optional.of(UserPreferences.builder().notifyByEmail(true).build()));
        dispatcher.deliver(user.getId(), NotificationType.NEW_MESSAGE, Map.of(), false, null);
        verify(mailGateway, never()).send(any(), any(), any());
    }

    @Test
    void sms_onlyForCritical_andOnlyIfPreferenceOn() {
        when(preferencesRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        dispatcher.deliver(user.getId(), NotificationType.BOOKING_CANCELLED, Map.of(), false, null);
        verify(smsService, never()).sendCritical(any(), any());

        dispatcher.deliver(user.getId(), NotificationType.BOOKING_CANCELLED, Map.of(), true, "Ekuiseo : annule");
        verify(smsService).sendCritical("+2290100000000", "Ekuiseo : annule");

        when(preferencesRepository.findByUserId(user.getId()))
                .thenReturn(Optional.of(UserPreferences.builder().notifyBySms(false).build()));
        dispatcher.deliver(user.getId(), NotificationType.TRIP_REMINDER, Map.of(), true, null);
        verify(smsService, never()).sendCritical(eq("+2290100000000"), org.mockito.ArgumentMatchers.contains("rappel"));
    }

    @Test
    void mailFailure_isSwallowed_andSmsStillGoes() {
        when(preferencesRepository.findByUserId(user.getId()))
                .thenReturn(Optional.of(UserPreferences.builder().notifyByEmail(true).build()));
        doThrow(new MailDeliveryException("SMTP down", new RuntimeException())).when(mailGateway).send(any(), any(), any());

        assertThatCode(() -> dispatcher.dispatch(user.getId(), NotificationType.ACCOUNT_SUSPENDED,
                Map.of("reason", "abus"), true, null)).doesNotThrowAnyException();
        verify(smsService).sendCritical(eq("+2290100000000"), org.mockito.ArgumentMatchers.contains("suspendu"));
    }

    @Test
    void deletedUser_receivesNothing() {
        user.setStatus(UserStatus.DELETED);
        when(preferencesRepository.findByUserId(user.getId()))
                .thenReturn(Optional.of(UserPreferences.builder().notifyByEmail(true).build()));

        dispatcher.deliver(user.getId(), NotificationType.NEW_MESSAGE, Map.of(), true, null);

        verify(mailGateway, never()).send(any(), any(), any());
        verify(smsService, never()).sendCritical(any(), any());
    }

    /* ------------------------------------------------------------------ Web Push (V20) */

    private PushSubscription subscription(String endpoint) {
        return PushSubscription.builder().id(UUID.randomUUID()).user(user).endpoint(endpoint)
                .p256dh("p256dh").auth("auth").failures(0).build();
    }

    @Test
    void push_sentToEveryDevice_whenEnabledAndPreferenceOn_withShortTextAndUrl() {
        when(preferencesRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        when(webPushSender.isEnabled()).thenReturn(true);
        PushSubscription phone = subscription("https://push.example/phone");
        PushSubscription laptop = subscription("https://push.example/laptop");
        when(pushSubscriptionRepository.findByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(phone, laptop));
        when(webPushSender.send(any(), any(), any(), any())).thenReturn(WebPushSender.Outcome.SENT);

        dispatcher.deliver(user.getId(), NotificationType.NEW_MESSAGE, Map.of("bookingId", "b-1"), false, null);

        ArgumentCaptor<NotificationTemplates.Push> content = ArgumentCaptor.forClass(NotificationTemplates.Push.class);
        verify(webPushSender).send(eq("https://push.example/phone"), eq("p256dh"), eq("auth"), content.capture());
        verify(webPushSender).send(eq("https://push.example/laptop"), eq("p256dh"), eq("auth"), any());
        assertThat(content.getValue().title()).isEqualTo("Nouveau message");
        assertThat(content.getValue().body()).doesNotStartWith("Ekuiseo :");
        assertThat(content.getValue().url()).isEqualTo("/bookings/b-1/messages");
        assertThat(phone.getLastUsedAt()).isNotNull();
        verify(pushSubscriptionRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void push_goneSubscription_isDeleted_andFailureIsCounted() {
        when(preferencesRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        when(webPushSender.isEnabled()).thenReturn(true);
        PushSubscription expired = subscription("https://push.example/expired");
        PushSubscription flaky = subscription("https://push.example/flaky");
        when(pushSubscriptionRepository.findByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(expired, flaky));
        when(webPushSender.send(eq("https://push.example/expired"), any(), any(), any())).thenReturn(WebPushSender.Outcome.GONE);
        when(webPushSender.send(eq("https://push.example/flaky"), any(), any(), any())).thenReturn(WebPushSender.Outcome.FAILED);

        dispatcher.deliver(user.getId(), NotificationType.TRIP_REMINDER, Map.of(), true, null);

        verify(pushSubscriptionRepository).delete(expired);
        assertThat(flaky.getFailures()).isEqualTo(1);
        verify(pushSubscriptionRepository).save(flaky);
        verify(pushSubscriptionRepository, never()).save(expired);
    }

    @Test
    void push_skipped_whenPreferenceOff_orPushDisabled() {
        when(webPushSender.isEnabled()).thenReturn(true);
        when(preferencesRepository.findByUserId(user.getId()))
                .thenReturn(Optional.of(UserPreferences.builder().notifyByPush(false).build()));
        dispatcher.deliver(user.getId(), NotificationType.NEW_MESSAGE, Map.of(), false, null);
        verify(pushSubscriptionRepository, never()).findByUserIdOrderByCreatedAtAsc(any());

        when(webPushSender.isEnabled()).thenReturn(false);
        when(preferencesRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        dispatcher.deliver(user.getId(), NotificationType.NEW_MESSAGE, Map.of(), false, null);
        verify(pushSubscriptionRepository, never()).findByUserIdOrderByCreatedAtAsc(any());
        verify(webPushSender, never()).send(any(), any(), any(), any());
    }
}
