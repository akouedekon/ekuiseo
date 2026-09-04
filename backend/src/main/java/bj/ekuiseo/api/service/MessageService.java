package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Conversation;
import bj.ekuiseo.api.domain.Message;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.dto.conversation.ConversationSummary;
import bj.ekuiseo.api.dto.message.MessageResponse;
import bj.ekuiseo.api.dto.message.SendMessageRequest;
import bj.ekuiseo.api.mapper.MessageMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.ConversationRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Messagerie liee a une reservation, entre le passager et le conducteur du trajet. */
@Service
public class MessageService {

    private final BookingRepository bookingRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final MessageMapper messageMapper;
    private final NotificationService notificationService;

    public MessageService(BookingRepository bookingRepository, ConversationRepository conversationRepository,
                           MessageRepository messageRepository, UserRepository userRepository,
                           MessageMapper messageMapper, NotificationService notificationService) {
        this.bookingRepository = bookingRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.messageMapper = messageMapper;
        this.notificationService = notificationService;
    }

    @Transactional
    public MessageResponse send(UUID bookingId, UUID senderId, SendMessageRequest req) {
        Booking booking = findBooking(bookingId);
        assertParticipant(booking, senderId);
        User sender = userRepository.findById(senderId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));

        Conversation conversation = conversationRepository.findByBookingId(bookingId)
                .orElseGet(() -> conversationRepository.save(Conversation.builder().booking(booking).build()));

        Message message = Message.builder()
                .conversation(conversation)
                .sender(sender)
                .body(req.body())
                .build();
        message = messageRepository.save(message);

        User recipient = booking.getPassenger().getId().equals(senderId)
                ? booking.getTrip().getDriver() : booking.getPassenger();
        notificationService.notify(recipient, NotificationType.NEW_MESSAGE,
                Map.of("bookingId", bookingId.toString(), "messageId", message.getId().toString()));

        return messageMapper.toResponse(message);
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> list(UUID bookingId, UUID requesterId) {
        Booking booking = findBooking(bookingId);
        assertParticipant(booking, requesterId);
        return conversationRepository.findByBookingId(bookingId)
                .map(c -> messageRepository.findByConversationIdOrderByCreatedAtAsc(c.getId()).stream()
                        .map(messageMapper::toResponse).toList())
                .orElse(List.of());
    }

    /**
     * GET /api/v1/me/conversations : une conversation par reservation ou
     * l'utilisateur est participant (passager ou conducteur), avec dernier message
     * et compteur de non-lus. N+1 assume sur le dernier message/compteur (2
     * requetes par conversation) : liste typiquement courte (une par reservation
     * de l'utilisateur), voir ConversationRepository#findAllForParticipant pour le
     * chargement anticipe du reste (reservation/trajet/participants).
     */
    @Transactional(readOnly = true)
    public List<ConversationSummary> myConversations(UUID userId) {
        return conversationRepository.findAllForParticipant(userId).stream()
                .map(c -> toSummary(c, userId)).toList();
    }

    private ConversationSummary toSummary(Conversation conversation, UUID userId) {
        Booking booking = conversation.getBooking();
        Trip trip = booking.getTrip();
        User counterpart = booking.getPassenger().getId().equals(userId) ? trip.getDriver() : booking.getPassenger();
        Message last = messageRepository.findFirstByConversationIdOrderByCreatedAtDesc(conversation.getId()).orElse(null);
        long unread = messageRepository.countByConversationIdAndReadAtIsNullAndSenderIdNot(conversation.getId(), userId);
        return new ConversationSummary(booking.getId(), trip.getId(),
                new ConversationSummary.CounterpartRef(counterpart.getId(), counterpart.getFirstName(),
                        counterpart.getLastName(), counterpart.getPhotoUrl()),
                trip.getOriginLabel(), trip.getDestLabel(), trip.getDepartureAt(),
                last != null ? last.getBody() : null, last != null ? last.getCreatedAt() : null, unread);
    }

    private void assertParticipant(Booking booking, UUID userId) {
        boolean isPassenger = booking.getPassenger().getId().equals(userId);
        boolean isDriver = booking.getTrip().getDriver().getId().equals(userId);
        if (!isPassenger && !isDriver) {
            throw new ForbiddenException("Vous n'etes pas autorise a acceder a cette conversation");
        }
    }

    private Booking findBooking(UUID id) {
        return bookingRepository.findById(id).orElseThrow(() -> new NotFoundException("Reservation introuvable"));
    }
}
