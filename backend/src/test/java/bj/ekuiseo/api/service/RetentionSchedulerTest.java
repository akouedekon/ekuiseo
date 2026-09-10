package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.repository.ConversationRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.NotificationRepository;
import bj.ekuiseo.api.repository.OtpCodeRepository;
import bj.ekuiseo.api.repository.SearchAlertRepository;
import bj.ekuiseo.api.repository.TripPositionRepository;
import bj.ekuiseo.api.repository.TripRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Purges nocturnes (constats F120/F516/F553/F525) : chaque seuil derive des durees
 * configurees, chaque purge tourne dans sa propre transaction, et l echec de l une ne
 * bloque pas les suivantes.
 */
class RetentionSchedulerTest {

    private final OtpCodeRepository otpCodes = mock(OtpCodeRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final SearchAlertRepository searchAlerts = mock(SearchAlertRepository.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus txStatus = mock(TransactionStatus.class);

    private final IdentityDocumentService identityDocuments = mock(IdentityDocumentService.class);
    private final TripPositionRepository tripPositions = mock(TripPositionRepository.class);
    private final TripRepository trips = mock(TripRepository.class);

    private final RetentionScheduler scheduler = new RetentionScheduler(otpCodes, notifications, messages,
            conversations, searchAlerts, identityDocuments, tripPositions, trips, txManager, 24, 180, 180, 90, 30, 24);

    private final Instant now = Instant.parse("2026-09-07T02:30:00Z"); // 03:30 a Porto-Novo

    @Test
    void eachPurge_usesItsConfiguredThreshold_andRunsInItsOwnTransaction() {
        when(txManager.getTransaction(any())).thenReturn(txStatus);
        when(otpCodes.deleteByExpiresAtBefore(any())).thenReturn(3);
        when(notifications.deleteByCreatedAtBefore(any())).thenReturn(10);
        when(messages.deleteForTripsDepartedBefore(any())).thenReturn(7);
        when(conversations.deleteEmptyForTripsDepartedBefore(any())).thenReturn(2);
        when(searchAlerts.deactivateExpired(any())).thenReturn(4);
        when(searchAlerts.deleteInactiveCreatedBefore(any())).thenReturn(1);
        when(identityDocuments.purgeDecidedBefore(any())).thenReturn(2);
        when(tripPositions.deleteByRecordedAtBefore(any())).thenReturn(5);
        when(trips.disableLiveSharingForStatusesUpdatedBefore(any(), any())).thenReturn(1);

        int total = scheduler.purgeAll(now);

        assertThat(total).isEqualTo(35);
        // V23 : positions du suivi en direct de plus de 24 h, partage coupe sur les trajets finis depuis autant.
        verify(tripPositions).deleteByRecordedAtBefore(now.minus(24, ChronoUnit.HOURS));
        verify(trips).disableLiveSharingForStatusesUpdatedBefore(
                List.of(TripStatus.COMPLETED, TripStatus.CANCELLED), now.minus(24, ChronoUnit.HOURS));
        // V20 : pieces d identite des dossiers decides depuis plus de 30 jours.
        verify(identityDocuments).purgeDecidedBefore(now.minus(30, ChronoUnit.DAYS));
        verify(otpCodes).deleteByExpiresAtBefore(now.minus(24, ChronoUnit.HOURS));
        verify(notifications).deleteByCreatedAtBefore(now.minus(180, ChronoUnit.DAYS));
        verify(messages).deleteForTripsDepartedBefore(now.minus(180, ChronoUnit.DAYS));
        verify(conversations).deleteEmptyForTripsDepartedBefore(now.minus(180, ChronoUnit.DAYS));
        verify(searchAlerts).deactivateExpired(LocalDate.ofInstant(now, Tz.BENIN));
        verify(searchAlerts).deleteInactiveCreatedBefore(now.minus(90, ChronoUnit.DAYS));
        // Neuf purges, neuf transactions : un echec n en annule pas une autre.
        verify(txManager, times(9)).getTransaction(any());
        verify(txManager, times(9)).commit(txStatus);
        verify(txManager, never()).rollback(any());
    }

    @Test
    void aFailingPurge_isLoggedAndRolledBack_withoutBlockingTheOthers() {
        when(txManager.getTransaction(any())).thenReturn(txStatus);
        when(otpCodes.deleteByExpiresAtBefore(any())).thenReturn(1);
        when(notifications.deleteByCreatedAtBefore(any())).thenThrow(new IllegalStateException("table verrouillee"));
        when(messages.deleteForTripsDepartedBefore(any())).thenReturn(5);
        when(conversations.deleteEmptyForTripsDepartedBefore(any())).thenReturn(0);
        when(searchAlerts.deactivateExpired(any())).thenReturn(0);
        when(searchAlerts.deleteInactiveCreatedBefore(any())).thenReturn(2);

        int total = scheduler.purgeAll(now);

        assertThat(total).isEqualTo(8);
        verify(messages).deleteForTripsDepartedBefore(any());
        verify(searchAlerts).deleteInactiveCreatedBefore(any());
        verify(txManager).rollback(txStatus);
        verify(txManager, times(8)).commit(txStatus);
    }

    @Test
    void messagesAndConversations_shareTheSameCutoff() {
        when(txManager.getTransaction(any())).thenReturn(txStatus);
        ArgumentCaptor<Instant> messagesCutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> conversationsCutoff = ArgumentCaptor.forClass(Instant.class);

        scheduler.purgeAll(now);

        verify(messages).deleteForTripsDepartedBefore(messagesCutoff.capture());
        verify(conversations).deleteEmptyForTripsDepartedBefore(conversationsCutoff.capture());
        assertThat(conversationsCutoff.getValue()).isEqualTo(messagesCutoff.getValue());
    }
}
