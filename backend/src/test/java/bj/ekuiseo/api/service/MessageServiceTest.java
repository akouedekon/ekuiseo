package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Conversation;
import bj.ekuiseo.api.domain.Message;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.message.SendMessageRequest;
import bj.ekuiseo.api.mapper.MessageMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.ConversationRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constat F546 : la conversation suit le cycle de vie de la reservation ; la lecture reste ouverte. */
class MessageServiceTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final MessageService service = new MessageService(bookingRepository, conversationRepository,
            messageRepository, userRepository, messageMapper, notificationService);

    private final User passenger = User.builder().id(UUID.randomUUID()).firstName("Awa").lastName("K").build();
    private final User driver = User.builder().id(UUID.randomUUID()).firstName("Koffi").lastName("A").build();
    private Trip trip;
    private Booking booking;

    @BeforeEach
    void setUp() {
        trip = Trip.builder().id(UUID.randomUUID()).driver(driver).originLabel("Cotonou").destLabel("Bohicon")
                .departureAt(Instant.now().plus(2, ChronoUnit.DAYS)).status(TripStatus.PUBLISHED).build();
        booking = Booking.builder().id(UUID.randomUUID()).trip(trip).passenger(passenger).seats(1)
                .status(BookingStatus.CONFIRMED).build();
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(userRepository.findById(passenger.getId())).thenReturn(Optional.of(passenger));
        when(userRepository.findById(driver.getId())).thenReturn(Optional.of(driver));
        when(conversationRepository.findByBookingId(booking.getId())).thenReturn(Optional.empty());
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> {
            Conversation c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
    }

    @Test
    void send_onConfirmedBooking_createsConversation_andNotifiesTheOtherParty() {
        service.send(booking.getId(), passenger.getId(), new SendMessageRequest("Bonjour, je serai a l heure"));

        verify(conversationRepository).save(any(Conversation.class));
        verify(messageRepository).save(any(Message.class));
        verify(notificationService).notify(eq(driver), eq(NotificationType.NEW_MESSAGE), any());
    }

    @Test
    void send_onPendingPaymentBooking_isAllowed() {
        booking.setStatus(BookingStatus.PENDING_PAYMENT);

        service.send(booking.getId(), driver.getId(), new SendMessageRequest("Ou vous prendre ?"));

        verify(notificationService).notify(eq(passenger), eq(NotificationType.NEW_MESSAGE), any());
    }

    @Test
    void send_onCancelledOrExpiredBooking_isForbidden_butListStillWorks() {
        for (BookingStatus closed : List.of(BookingStatus.CANCELLED_BY_PASSENGER, BookingStatus.CANCELLED_BY_DRIVER, BookingStatus.EXPIRED)) {
            booking.setStatus(closed);
            assertThatThrownBy(() -> service.send(booking.getId(), passenger.getId(), new SendMessageRequest("x")))
                    .isInstanceOf(ForbiddenException.class).hasMessageContaining("close");
        }
        verify(messageRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any());

        assertThat(service.list(booking.getId(), passenger.getId())).isEmpty();
    }

    @Test
    void send_onCancelledTrip_isForbidden_evenIfBookingStillConfirmed() {
        trip.setStatus(TripStatus.CANCELLED);

        assertThatThrownBy(() -> service.send(booking.getId(), passenger.getId(), new SendMessageRequest("x")))
                .isInstanceOf(ForbiddenException.class);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void send_afterTrip_isAllowedForSevenDays_thenClosed() {
        booking.setStatus(BookingStatus.COMPLETED);
        trip.setDepartureAt(Instant.now().minus(3, ChronoUnit.DAYS));
        service.send(booking.getId(), passenger.getId(), new SendMessageRequest("Merci pour le trajet"));
        verify(messageRepository).save(any(Message.class));

        booking.setStatus(BookingStatus.NO_SHOW);
        trip.setDepartureAt(Instant.now().minus(8, ChronoUnit.DAYS));
        assertThatThrownBy(() -> service.send(booking.getId(), driver.getId(), new SendMessageRequest("x")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void isWritable_windowIsExactlySevenDaysAfterDeparture() {
        Instant departure = Instant.parse("2026-09-12T07:30:00Z");
        trip.setDepartureAt(departure);
        booking.setStatus(BookingStatus.COMPLETED);

        assertThat(MessageService.isWritable(booking, departure.plus(7, ChronoUnit.DAYS).minusSeconds(1))).isTrue();
        assertThat(MessageService.isWritable(booking, departure.plus(7, ChronoUnit.DAYS))).isFalse();
    }

    @Test
    void send_byAThirdParty_isForbidden() {
        assertThatThrownBy(() -> service.send(booking.getId(), UUID.randomUUID(), new SendMessageRequest("x")))
                .isInstanceOf(ForbiddenException.class);
    }
}
