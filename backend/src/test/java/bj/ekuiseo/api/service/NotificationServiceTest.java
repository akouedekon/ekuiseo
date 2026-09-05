package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.Notification;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.mapper.NotificationMapper;
import bj.ekuiseo.api.repository.NotificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Routeur de notifications (constat F107) : in-app toujours en base, canaux sortants
 * confies au dispatcher apres commit seulement, et jamais bloquants pour l appelant.
 */
class NotificationServiceTest {

    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
    private final NotificationService service =
            new NotificationService(notificationRepository, mock(NotificationMapper.class), dispatcher);

    private final User user = User.builder().id(UUID.randomUUID()).firstName("Awa").phone("+2290100000000").build();

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void notify_savesInApp_andDispatchesOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        service.notify(user, NotificationType.NEW_MESSAGE, Map.of("bookingId", "b1"));

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(NotificationType.NEW_MESSAGE);
        assertThat(saved.getValue().getUser()).isSameAs(user);
        // Rien ne part tant que la transaction n est pas validee.
        verify(dispatcher, never()).dispatch(any(), any(), anyMap(), anyBoolean(), any());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(dispatcher).dispatch(eq(user.getId()), eq(NotificationType.NEW_MESSAGE), eq(Map.of("bookingId", "b1")),
                eq(false), isNull());
    }

    @Test
    void notifyCritical_flagsSmsChannel_andKeepsCallerText() {
        TransactionSynchronizationManager.initSynchronization();

        service.notifyCritical(user, NotificationType.TRIP_REMINDER, Map.of("tripId", "t1"), "Ekuiseo : rappel");
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(dispatcher).dispatch(eq(user.getId()), eq(NotificationType.TRIP_REMINDER), anyMap(), eq(true), eq("Ekuiseo : rappel"));
    }

    @Test
    void withoutTransaction_dispatchesImmediately() {
        service.notify(user, NotificationType.NEW_REVIEW, Map.of("rating", "5"));

        verify(dispatcher).dispatch(eq(user.getId()), eq(NotificationType.NEW_REVIEW), anyMap(), eq(false), isNull());
    }

    @Test
    void dispatchFailure_neverReachesCaller() {
        doThrow(new IllegalStateException("executeur arrete"))
                .when(dispatcher).dispatch(any(), any(), anyMap(), anyBoolean(), any());

        assertThatCode(() -> service.notify(user, NotificationType.PAYMENT_FAILED, Map.of())).doesNotThrowAnyException();
        verify(notificationRepository).save(any(Notification.class));
    }
}
