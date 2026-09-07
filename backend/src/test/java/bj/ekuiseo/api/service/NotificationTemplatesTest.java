package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.enums.NotificationType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 2 : chaque nouveau type a un gabarit ; objets et mentions exiges par les constats F523, F037, F010, F550. */
class NotificationTemplatesTest {

    @Test
    void everyTypeRenders_withAnEmptyPayload() {
        for (NotificationType type : NotificationType.values()) {
            NotificationTemplates.Rendered rendered = NotificationTemplates.render(type, Map.of());
            assertThat(rendered.subject()).as(type.name()).isNotBlank();
            assertThat(rendered.body()).as(type.name()).isNotBlank();
            assertThat(rendered.sms()).as(type.name()).isNotBlank();
        }
    }

    @Test
    void searchAlertMatch_hasAnExplicitSubject_andADirectLink() {
        UUID tripId = UUID.randomUUID();
        NotificationTemplates.Rendered rendered = NotificationTemplates.render(NotificationType.SEARCH_ALERT_MATCH,
                NotificationTemplates.payload("tripId", tripId.toString(), "route", "Cotonou -> Bohicon",
                        "departureAt", "2026-09-12T06:30:00Z", "pricePerSeatFcfa", 2500L, "seatsAvailable", 3));

        assertThat(rendered.subject()).isEqualTo("Un trajet Cotonou -> Bohicon, sam. 12 sept. 07:30");
        assertThat(rendered.body()).contains("https://ekuiseo.com/trips/" + tripId).contains("Prix par place");
        assertThat(rendered.body()).contains("Places disponibles : 3");
    }

    @Test
    void cancellationWithManualRefund_mentionsFiveBusinessDays() {
        NotificationTemplates.Rendered manual = NotificationTemplates.render(NotificationType.BOOKING_CANCELLED,
                NotificationTemplates.payload("cancelledBy", "PASSENGER", "forPassenger", true,
                        "refundAmountFcfa", 500L, "refundStatus", "MANUAL_REQUIRED"));
        assertThat(manual.body()).contains("Vous avez annule votre reservation").contains("sous 5 jours ouvres");

        NotificationTemplates.Rendered requested = NotificationTemplates.render(NotificationType.BOOKING_CANCELLED,
                NotificationTemplates.payload("cancelledBy", "PASSENGER", "forPassenger", true,
                        "refundAmountFcfa", 1000L, "refundStatus", "REQUESTED"));
        assertThat(requested.body()).doesNotContain("5 jours ouvres").contains("48 heures");

        // Le conducteur, lui, recoit l avis d annulation sans ligne de remboursement.
        NotificationTemplates.Rendered driver = NotificationTemplates.render(NotificationType.BOOKING_CANCELLED,
                NotificationTemplates.payload("cancelledBy", "PASSENGER", "refundAmountFcfa", 500L, "refundStatus", "MANUAL_REQUIRED"));
        assertThat(driver.body()).contains("Un passager a annule").doesNotContain("rembourse");
    }

    @Test
    void bookingExpired_andReportReceived_areWordedForTheirRecipients() {
        NotificationTemplates.Rendered expired = NotificationTemplates.render(NotificationType.BOOKING_EXPIRED,
                NotificationTemplates.payload("ttlMinutes", 20, "route", "Cotonou -> Bohicon"));
        assertThat(expired.subject()).contains("expiree");
        assertThat(expired.body()).contains("20 minutes").contains("remises a disposition");

        NotificationTemplates.Rendered received = NotificationTemplates.render(NotificationType.REPORT_RECEIVED,
                NotificationTemplates.payload("reason", "NO_SHOW"));
        assertThat(received.subject()).isEqualTo("Un signalement vous concerne");
        assertThat(received.body()).contains("absence au depart").doesNotContain("reporter");
    }

    @Test
    void payoutAndSubscriptionTemplates_carryTheUsefulFacts() {
        NotificationTemplates.Rendered settled = NotificationTemplates.render(NotificationType.PAYOUT_SETTLED,
                NotificationTemplates.payload("amountFcfa", 18_400L, "destination", "+2290197000322", "externalReference", "MP-123"));
        assertThat(settled.body()).contains("MP-123").contains("+2290197000322");

        NotificationTemplates.Rendered failed = NotificationTemplates.render(NotificationType.PAYOUT_FAILED,
                NotificationTemplates.payload("amountFcfa", 18_400L, "reason", "Compte inactif"));
        assertThat(failed.body()).contains("Compte inactif");

        NotificationTemplates.Rendered expiring = NotificationTemplates.render(NotificationType.SUBSCRIPTION_EXPIRING,
                NotificationTemplates.payload("currentPeriodEnd", "2026-09-15T10:00:00Z"));
        assertThat(expiring.body()).contains("2026").contains("Renouvelez");
    }
}
