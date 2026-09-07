package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Conversation;
import bj.ekuiseo.api.domain.Message;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.conversation.ConversationSummary;
import bj.ekuiseo.api.dto.message.MessageResponse;
import bj.ekuiseo.api.dto.message.SendMessageRequest;
import bj.ekuiseo.api.mapper.MessageMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.ConversationRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Messagerie liee a une reservation, entre le passager et le conducteur du trajet.
 *
 * <p>Phase 2 (constat F546) : la conversation suit le cycle de vie de la reservation. On
 * ecrit tant que la reservation est PENDING_PAYMENT ou CONFIRMED, puis, une fois le trajet
 * effectue (COMPLETED / NO_SHOW), pendant {@link #POST_TRIP_WINDOW} apres le depart (le
 * temps de regler un oubli ou un litige) ; jamais sur une reservation annulee, expiree, ni
 * sur un trajet annule. La lecture reste possible dans tous les cas.</p>
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    /** Fenetre d ecriture apres le depart d un trajet effectue (J+7). */
    static final Duration POST_TRIP_WINDOW = Duration.ofDays(7);

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
        assertWritable(booking, Instant.now());
        User sender = userRepository.findById(senderId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));

        Conversation conversation = conversationRepository.findByBookingId(bookingId)
                .orElseGet(() -> conversationRepository.save(Conversation.builder().booking(booking).build()));

        Message message = Message.builder()
                .conversation(conversation)
                .sender(sender)
                .body(req.body())
                .build();
        message = messageRepository.save(message);
        // Trace applicative structuree (constat F555) : qui ecrit, sur quelle reservation, combien
        // - jamais le contenu. Sert a l analyse d un abus a posteriori dans les journaux Docker.
        log.info("Message envoye : messageId={} senderId={} bookingId={} longueur={}",
                message.getId(), senderId, bookingId, req.body() == null ? 0 : req.body().length());

        User recipient = booking.getPassenger().getId().equals(senderId)
                ? booking.getTrip().getDriver() : booking.getPassenger();
        notificationService.notify(recipient, NotificationType.NEW_MESSAGE,
                Map.of("bookingId", bookingId.toString(), "messageId", message.getId().toString()));

        return messageMapper.toResponse(message);
    }

    /**
     * Conversation ouverte a l ecriture ? Reservation PENDING_PAYMENT ou CONFIRMED sur un
     * trajet non annule ; ou COMPLETED / NO_SHOW jusqu a J+7 apres le depart. 403
     * « conversation close » sinon (constat F546).
     */
    static boolean isWritable(Booking booking, Instant now) {
        Trip trip = booking.getTrip();
        if (trip.getStatus() == TripStatus.CANCELLED) {
            return false;
        }
        BookingStatus status = booking.getStatus();
        if (status == BookingStatus.PENDING_PAYMENT || status == BookingStatus.PENDING_DRIVER_APPROVAL
                || status == BookingStatus.CONFIRMED) {
            return true;
        }
        if (status == BookingStatus.COMPLETED || status == BookingStatus.NO_SHOW) {
            return trip.getDepartureAt() != null && now.isBefore(trip.getDepartureAt().plus(POST_TRIP_WINDOW));
        }
        return false;
    }

    private static void assertWritable(Booking booking, Instant now) {
        if (!isWritable(booking, now)) {
            throw new ForbiddenException("Cette conversation est close : la reservation est terminee, annulee ou expiree");
        }
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
