package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.common.FeePolicy;
import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripStop;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.booking.BookingDetailResponse;
import bj.ekuiseo.api.dto.booking.BookingQuoteRequest;
import bj.ekuiseo.api.dto.booking.BookingResponse;
import bj.ekuiseo.api.dto.booking.CreateBookingRequest;
import bj.ekuiseo.api.dto.payment.PaymentPlanResponse;
import bj.ekuiseo.api.dto.trip.RecurringTripResponse;
import bj.ekuiseo.api.mapper.BookingMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverSubscriptionRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.ReviewRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.TripStopRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final List<BookingStatus> ACTIVE_STATUSES =
            List.of(BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED);

    private final BookingRepository bookingRepository;
    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final UserRepository userRepository;
    private final DriverSubscriptionRepository driverSubscriptionRepository;
    private final MessageRepository messageRepository;
    private final ReviewRepository reviewRepository;
    private final BookingMapper bookingMapper;
    private final CancellationPolicy cancellationPolicy;
    private final DriverCancellationPolicy driverCancellationPolicy;
    private final NotificationService notificationService;
    private final PaymentService paymentService;
    private final AuditService auditService;
    private final FeePolicy feePolicy;
    private final int pendingPaymentTtlMinutes;

    public BookingService(BookingRepository bookingRepository, TripRepository tripRepository,
                           TripStopRepository tripStopRepository, UserRepository userRepository, DriverSubscriptionRepository driverSubscriptionRepository,
                           MessageRepository messageRepository, ReviewRepository reviewRepository, BookingMapper bookingMapper,
                           CancellationPolicy cancellationPolicy, DriverCancellationPolicy driverCancellationPolicy,
                           NotificationService notificationService, PaymentService paymentService,
                           AuditService auditService, FeePolicy feePolicy,
                           @Value("${ekuiseo.booking.pending-payment-ttl-minutes:20}") int pendingPaymentTtlMinutes) {
        this.bookingRepository = bookingRepository;
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.userRepository = userRepository;
        this.driverSubscriptionRepository = driverSubscriptionRepository;
        this.messageRepository = messageRepository;
        this.reviewRepository = reviewRepository;
        this.bookingMapper = bookingMapper;
        this.cancellationPolicy = cancellationPolicy;
        this.driverCancellationPolicy = driverCancellationPolicy;
        this.notificationService = notificationService;
        this.paymentService = paymentService;
        this.auditService = auditService;
        this.feePolicy = feePolicy;
        this.pendingPaymentTtlMinutes = pendingPaymentTtlMinutes;
    }

    /**
     * Cree une reservation. La decrementation des places disponibles est atomique
     * (UPDATE conditionnel en base, regle metier n.1) : si deux passagers tentent de
     * reserver la derniere place au meme instant, un seul y parvient et l'autre recoit
     * un 409 Conflict, sans jamais laisser seats_available passer sous zero.
     */
    @Transactional
    public BookingResponse createBooking(UUID tripId, UUID passengerId, CreateBookingRequest req) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new NotFoundException("Trajet introuvable"));
        assertBookable(trip);
        if (trip.getDriver().getId().equals(passengerId)) {
            // Regle metier n.5 : un conducteur ne peut pas reserver son propre trajet.
            throw new ForbiddenException("Un conducteur ne peut pas reserver son propre trajet");
        }
        if (bookingRepository.existsByTripIdAndPassengerIdAndStatusIn(tripId, passengerId, ACTIVE_STATUSES)) {
            throw new ConflictException("Vous avez deja une reservation active sur ce trajet");
        }
        User passenger = userRepository.findById(passengerId)
                .orElseThrow(() -> new NotFoundException("Passager introuvable"));

        int affected = tripRepository.decrementSeatsIfAvailable(tripId, req.seats());
        if (affected == 0) {
            throw new ConflictException("Plus assez de places disponibles sur ce trajet");
        }
        // Le trajet est complet si, apres decrementation, il ne reste plus de place.
        Trip refreshed = tripRepository.findById(tripId).orElseThrow();
        if (refreshed.getSeatsAvailable() == 0) {
            tripRepository.updateStatus(tripId, TripStatus.FULL);
        }

        // Regle metier n.11 : commission ramenee a 0% si le conducteur est abonne.
        boolean commissionWaived = driverSubscriptionRepository.hasActiveSubscription(trip.getDriver().getId(), Instant.now());
        PaymentMethod method = resolvePaymentMethod(req.paymentMode());
        BookingAmounts amounts = computeAmounts(resolveUnitPrice(trip, req.dropoffStopId()), req.seats(), commissionWaived, method);
        boolean isCash = method == PaymentMethod.CASH;

        Booking booking = Booking.builder()
                .trip(trip)
                .passenger(passenger)
                .seats(req.seats())
                .pickupStopId(req.pickupStopId())
                .dropoffStopId(req.dropoffStopId())
                .amount(amounts.amount())
                .serviceFee(amounts.serviceFee())
                .depositAmount(amounts.depositAmount())
                .balanceDueOnBoard(amounts.balanceDueOnBoard())
                .paymentMethod(method)
                // Le paiement especes est confirme immediatement (regle au comptant a bord ;
                // une veritable validation du conducteur pour ce mode n'est PAS implementee
                // dans cette passe, voir README "ce qui reste a faire" - a specifier avant
                // d'ouvrir ce mode a des conducteurs non verifies). Le paiement mobile money
                // (acompte ou totalite selon le mode) reste PENDING_PAYMENT jusqu'au webhook
                // Kkiapay confirmant l'encaissement de deposit_amount.
                .status(isCash ? BookingStatus.CONFIRMED : BookingStatus.PENDING_PAYMENT)
                // Echeance de l acompte (V12) : le scheduler d expiration lit cette colonne, que
                // PaymentService#initiate prolonge si le paiement est lance juste avant la limite.
                .expiresAt(isCash ? null : Instant.now().plus(pendingPaymentTtlMinutes, ChronoUnit.MINUTES))
                .build();
        booking = bookingRepository.save(booking);

        if (isCash) {
            notificationService.notify(trip.getDriver(), NotificationType.BOOKING_CONFIRMED,
                    NotificationTemplates.payload("bookingId", booking.getId().toString(), "tripId", trip.getId().toString(),
                            "passengerName", booking.getPassenger().getFirstName(), "seats", booking.getSeats(),
                            "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                            "departureAt", java.util.Objects.toString(trip.getDepartureAt(), "")));
        }
        return bookingMapper.toResponse(booking);
    }

    /**
     * Devis de reservation (POST /api/v1/trips/{id}/booking-quote, voir
     * BookingQuoteRequest) : calcule exactement le meme {@link PaymentPlanResponse}
     * qu'une reservation reelle produirait, SANS rien creer en base ni decrementer
     * de places - via {@link #computeAmounts}, la meme methode que
     * {@link #createBooking}, pour que devis et reservation ne puissent jamais
     * diverger. Reprend volontairement les memes verifications que
     * {@code createBooking} (trajet PUBLISHED, pas son propre conducteur), dans
     * le meme ordre, pour que "le devis dit oui" implique fiablement "la
     * reservation reussira" (a la concurrence pres sur les toutes dernieres
     * places, la decrementation atomique restant le seul arbitre final - voir
     * javadoc de createBooking).
     */
    @Transactional(readOnly = true)
    public PaymentPlanResponse quote(UUID tripId, UUID requesterId, BookingQuoteRequest req) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new NotFoundException("Trajet introuvable"));
        assertBookable(trip);
        if (trip.getDriver().getId().equals(requesterId)) {
            // Regle metier n.5 : un conducteur ne peut pas reserver son propre trajet.
            throw new ForbiddenException("Un conducteur ne peut pas reserver son propre trajet");
        }
        if (trip.getSeatsAvailable() < req.seats()) {
            // Pas de decrementation atomique ici (rien n'est reserve) : une verification
            // simple suffit, la course concurrente sur la toute derniere place reste geree
            // par createBooking#decrementSeatsIfAvailable au moment de la reservation reelle.
            throw new ConflictException("Plus assez de places disponibles sur ce trajet");
        }

        boolean commissionWaived = driverSubscriptionRepository.hasActiveSubscription(trip.getDriver().getId(), Instant.now());
        PaymentMethod method = resolvePaymentMethod(req.paymentMode());
        BookingAmounts amounts = computeAmounts(resolveUnitPrice(trip, req.dropoffStopId()), req.seats(), commissionWaived, method);
        boolean isCash = method == PaymentMethod.CASH;
        // Aucune reservation n'existe encore : pas de createdAt reel pour ancrer l'echeance
        // de l'acompte, Instant.now() sert d'estimation "si vous reservez maintenant" -
        // recalculee exactement au moment de la reservation reelle (voir buildPaymentPlan).
        Instant depositDueAt = isCash ? null : Instant.now().plus(pendingPaymentTtlMinutes, ChronoUnit.MINUTES);
        return new PaymentPlanResponse(amounts.amount(), amounts.depositAmount(), amounts.balanceDueOnBoard(),
                amounts.serviceFee(), method, "PENDING", depositDueAt,
                (int) CancellationPolicy.FREE_CANCELLATION_WINDOW.toHours());
    }

    /**
     * Un trajet est reservable s il est PUBLISHED (ni modele TEMPLATE, ni FULL, ni parti),
     * que son depart est a venir et que son conducteur n est pas suspendu (constats
     * F035/F039/F202). Partage par {@link #createBooking} et {@link #quote}.
     */
    private static void assertBookable(Trip trip) {
        if (trip.getStatus() != TripStatus.PUBLISHED) {
            throw new ConflictException("Ce trajet n accepte plus de reservations");
        }
        if (!Instant.now().isBefore(trip.getDepartureAt())) {
            throw new ConflictException("Ce trajet est deja parti");
        }
        if (trip.getDriver().getStatus() != bj.ekuiseo.api.domain.enums.UserStatus.ACTIVE) {
            throw new ConflictException("Ce conducteur n est plus disponible");
        }
    }

    /**
     * Prix unitaire de la reservation : le tarif du troncon jusqu'a l'arret de descente
     * quand il est precise (tarif par troncon, voir TripStop#priceFromOrigin), sinon le
     * prix du trajet complet. Un arret qui n'appartient pas au trajet est refuse.
     */
    private long resolveUnitPrice(Trip trip, UUID dropoffStopId) {
        if (dropoffStopId == null) {
            return trip.getPricePerSeat();
        }
        return tripStopRepository.findByTripIdOrderByPosition(trip.getId()).stream()
                .filter(stop -> stop.getId().equals(dropoffStopId))
                .findFirst()
                .map(TripStop::getPriceFromOrigin)
                .orElseThrow(() -> new BadRequestException("Arret de descente inconnu pour ce trajet"));
    }

    /** {@code MOMO_DEPOSIT} si absent (regle metier n.21) - voir CreateBookingRequest/BookingQuoteRequest. */
    private PaymentMethod resolvePaymentMethod(PaymentMethod requested) {
        return requested != null ? requested : PaymentMethod.MOMO_DEPOSIT;
    }

    /**
     * Decomposition montant total / frais de service / acompte / solde a bord
     * (regle metier n.21, migration V7), factorisee pour que {@link #createBooking}
     * et {@link #quote} appliquent rigoureusement la meme FeePolicy et ne
     * puissent jamais diverger. Voir FeePolicy#computeDepositAmount pour la
     * justification du max(acompte de base, frais de service) en MOMO_DEPOSIT.
     */
    private BookingAmounts computeAmounts(long pricePerSeat, int seats, boolean commissionWaived, PaymentMethod method) {
        long amount = pricePerSeat * seats;
        long serviceFee = feePolicy.computeServiceFee(amount, commissionWaived);
        long depositAmount = switch (method) {
            case CASH -> 0L;
            case MOMO_FULL -> amount;
            case MOMO_DEPOSIT -> feePolicy.computeDepositAmount(amount, serviceFee);
        };
        return new BookingAmounts(amount, serviceFee, depositAmount, amount - depositAmount);
    }

    private record BookingAmounts(long amount, long serviceFee, long depositAmount, long balanceDueOnBoard) {
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> myBookings(UUID passengerId) {
        return bookingRepository.findByPassengerIdOrderByCreatedAtDesc(passengerId).stream()
                .map(bookingMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(UUID id, UUID requesterId) {
        Booking booking = findBooking(id);
        assertParticipant(booking, requesterId);
        return bookingMapper.toResponse(booking);
    }

    /**
     * Reservations du passager, enrichies du trajet et du plan de paiement en une
     * seule requete JOIN FETCH (voir BookingRepository#findByPassengerIdWithTripFetched),
     * pour GET /api/v1/bookings?expand=trip,paymentPlan.
     */
    @Transactional(readOnly = true)
    public List<BookingDetailResponse> myBookingsDetailed(UUID passengerId) {
        return bookingRepository.findByPassengerIdWithTripFetched(passengerId).stream()
                .map(b -> toDetail(b, passengerId)).toList();
    }

    /** Variante detail d'une seule reservation, GET /api/v1/bookings/{id}?expand=trip,paymentPlan. */
    @Transactional(readOnly = true)
    public BookingDetailResponse getBookingDetailed(UUID id, UUID requesterId) {
        Booking booking = findBooking(id);
        assertParticipant(booking, requesterId);
        return toDetail(booking, requesterId);
    }

    private BookingDetailResponse toDetail(Booking booking, UUID requesterId) {
        var trip = booking.getTrip();
        var driver = trip.getDriver();
        var vehicle = trip.getVehicle();
        long unread = messageRepository.countByConversation_Booking_IdAndReadAtIsNullAndSenderIdNot(
                booking.getId(), requesterId);
        BookingDetailResponse.TripSummary tripSummary = new BookingDetailResponse.TripSummary(
                trip.getId(), trip.getTripType(), trip.getOriginLabel(), trip.getDestLabel(), trip.getDepartureAt(),
                trip.getPricePerSeat(),
                new BookingDetailResponse.DriverRef(driver.getId(), driver.getFirstName(), driver.getLastName(),
                        driver.getPhotoUrl(), driver.getRatingAvg()),
                new BookingDetailResponse.VehicleRef(vehicle.getBrand(), vehicle.getModel(), vehicle.getColor(),
                        vehicle.getComfortLevel()));
        return new BookingDetailResponse(booking.getId(), trip.getId(), booking.getPassenger().getId(),
                booking.getSeats(), booking.getAmount(), booking.getServiceFee(), booking.getStatus(),
                booking.getPaymentMethod(), booking.getCreatedAt(), buildPaymentPlan(booking), tripSummary, unread,
                reviewRepository.existsByTripIdAndAuthorIdAndTargetId(trip.getId(), requesterId, driver.getId()));
    }

    /**
     * Lit directement la decomposition figee sur la reservation (voir
     * PaymentPlanResponse pour le detail par mode de paiement, regle metier n.21).
     */
    private PaymentPlanResponse buildPaymentPlan(Booking booking) {
        boolean isCash = booking.getPaymentMethod() == PaymentMethod.CASH;
        Instant depositDueAt = (!isCash && booking.getStatus() == BookingStatus.PENDING_PAYMENT)
                ? (booking.getExpiresAt() != null ? booking.getExpiresAt()
                        : booking.getCreatedAt().plus(pendingPaymentTtlMinutes, ChronoUnit.MINUTES))
                : null;
        return new PaymentPlanResponse(booking.getAmount(), booking.getDepositAmount(), booking.getBalanceDueOnBoard(),
                booking.getServiceFee(), booking.getPaymentMethod(), paymentPlanStatus(booking), depositDueAt,
                (int) CancellationPolicy.FREE_CANCELLATION_WINDOW.toHours());
    }

    /**
     * Vue simplifiee de l'etat du paiement pour l'affichage (pas le statut brut
     * Kkiapay, voir GET /api/v1/payments/{paymentId} pour celui-ci) : distingue
     * un acompte deja encaisse d'un paiement integral, et signale explicitement
     * qu'un solde CASH n'est jamais "paye" au sens ou la plateforme l'aurait percu.
     */
    private String paymentPlanStatus(Booking booking) {
        BookingStatus status = booking.getStatus();
        if (status == BookingStatus.CANCELLED_BY_PASSENGER || status == BookingStatus.CANCELLED_BY_DRIVER) {
            return "CANCELLED";
        }
        if (status == BookingStatus.PENDING_PAYMENT) {
            return "PENDING";
        }
        return switch (booking.getPaymentMethod()) {
            case MOMO_FULL -> "PAID_IN_FULL";
            case MOMO_DEPOSIT -> "DEPOSIT_PAID";
            case CASH -> "CASH_DUE_ON_BOARD";
        };
    }

    /**
     * Heuristique "trajet recurrent du passager" (GET /api/v1/me/recurring-trips,
     * bloc "votre trajet de la semaine") : ce n'est PAS une preference enregistree
     * (aucune table dediee), mais une detection a la volee sur l'historique de
     * reservations actives/terminees du passager. Un axe (origine/destination,
     * identifie par ses libelles) est retenu des lors qu'il apparait dans au moins
     * deux reservations distinctes ; l'identifiant renvoye est deterministe
     * (derive de passager+axe) pour rester stable d'un appel a l'autre malgre
     * l'absence de ligne stockee.
     */
    @Transactional(readOnly = true)
    public List<RecurringTripResponse> myRecurringTrips(UUID passengerId) {
        List<Booking> history = bookingRepository.findByPassengerIdWithTripFetched(passengerId);
        Map<String, List<Booking>> byRoute = new LinkedHashMap<>();
        for (Booking b : history) {
            if (b.getStatus() == BookingStatus.CANCELLED_BY_PASSENGER || b.getStatus() == BookingStatus.CANCELLED_BY_DRIVER) {
                continue;
            }
            String key = b.getTrip().getOriginLabel() + "||" + b.getTrip().getDestLabel();
            byRoute.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
        }

        List<RecurringTripResponse> result = new ArrayList<>();
        Instant now = Instant.now();
        for (List<Booking> bookings : byRoute.values()) {
            if (bookings.size() < 2) {
                continue; // pas assez d'occurrences pour parler d'un trajet "habituel"
            }
            Booking mostRecent = bookings.stream().max(Comparator.comparing(Booking::getCreatedAt)).orElseThrow();
            var trip = mostRecent.getTrip();
            Set<DayOfWeek> weekdays = EnumSet.noneOf(DayOfWeek.class);
            for (Booking b : bookings) {
                weekdays.add(b.getTrip().getDepartureAt().atZone(Tz.BENIN).getDayOfWeek());
            }
            // Heure locale (Benin), formatee HH:mm pour l affichage (constat F415).
            LocalTime timeOfDay = trip.getDepartureAt().atZone(Tz.BENIN).toLocalTime().withSecond(0).withNano(0);
            long matches = tripRepository.countByOriginLabelAndDestLabelAndStatusAndDepartureAtAfterAndSeatsAvailableGreaterThan(
                    trip.getOriginLabel(), trip.getDestLabel(), bj.ekuiseo.api.domain.enums.TripStatus.PUBLISHED, now, 0);
            var next = tripRepository.findFirstByOriginLabelAndDestLabelAndStatusAndDepartureAtAfterAndSeatsAvailableGreaterThanOrderByDepartureAtAsc(
                    trip.getOriginLabel(), trip.getDestLabel(), bj.ekuiseo.api.domain.enums.TripStatus.PUBLISHED, now, 0);
            UUID virtualId = UUID.nameUUIDFromBytes(
                    (passengerId + "|" + trip.getOriginLabel() + "|" + trip.getDestLabel()).getBytes());
            result.add(new RecurringTripResponse(virtualId, trip.getOriginLabel(), trip.getOriginLat(), trip.getOriginLng(),
                    trip.getDestLabel(), trip.getDestLat(), trip.getDestLng(),
                    weekdays.stream().map(DayOfWeek::getValue).sorted().toList(),
                    timeOfDay.toString(), mostRecent.getSeats(), matches,
                    next.map(bj.ekuiseo.api.domain.Trip::getDepartureAt).orElse(null)));
        }
        return result;
    }

    /**
     * Annulation par le passager, avec application de la politique de remboursement
     * (regle n.7). Impossible une fois le trajet parti (constat F036) : la reservation
     * est alors cloturee par le cycle de vie (COMPLETED) ou signalee NO_SHOW par le
     * conducteur. Le conducteur est prevenu (par SMS si l annulation est tardive), le
     * passager recoit le detail du remboursement.
     */
    @Transactional
    public BookingResponse cancelByPassenger(UUID id, UUID passengerId) {
        Booking booking = findBooking(id);
        if (!booking.getPassenger().getId().equals(passengerId)) {
            throw new ForbiddenException("Cette reservation ne vous appartient pas");
        }
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT && booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BadRequestException("Cette reservation ne peut plus etre annulee");
        }
        Trip trip = booking.getTrip();
        Instant now = Instant.now();
        if (trip.getStatus() == TripStatus.ONGOING || trip.getStatus() == TripStatus.COMPLETED
                || !now.isBefore(trip.getDepartureAt())) {
            throw new BadRequestException("Le trajet est deja parti : la reservation ne peut plus etre annulee");
        }
        // Regle metier n.21 (point 3) : le bareme d annulation porte sur depositAmount,
        // seul montant reellement encaisse par la plateforme via Kkiapay (booking.amount
        // est le prix TOTAL, mais balanceDueOnBoard n a jamais transite par la plateforme -
        // il n y a donc rien a en rembourser, le passager ne le doit simplement plus
        // puisque le trajet n aura pas lieu).
        CancellationPolicy.Outcome outcome;
        if (booking.getFreeCancellationUntil() != null && now.isBefore(booking.getFreeCancellationUntil())) {
            // Le conducteur a modifie l horaire : annulation gratuite pendant 24 h (lot 1.3).
            outcome = new CancellationPolicy.Outcome(booking.getDepositAmount(), 0L,
                    "Annulation gratuite (horaire modifie par le conducteur)");
        } else {
            outcome = cancellationPolicy.evaluate(booking.getDepositAmount(), now, trip.getDepartureAt());
        }
        log.info("Annulation reservation {} : remboursement={} retenu={} ({})",
                booking.getId(), outcome.refundAmount(), outcome.retainedAmount(), outcome.reason());

        boolean wasConfirmed = booking.getStatus() == BookingStatus.CONFIRMED;
        booking.setStatus(BookingStatus.CANCELLED_BY_PASSENGER);
        booking.setExpiresAt(null);
        bookingRepository.save(booking);
        releaseSeats(trip.getId(), booking.getSeats());

        PaymentService.RefundOutcome refund = paymentService.refundBooking(booking, outcome.refundAmount(), "ANNULATION_PASSAGER");
        auditService.log(passengerId, "BOOKING_CANCELLED_BY_PASSENGER", "booking", booking.getId(),
                Map.of("refundAmountFcfa", outcome.refundAmount(), "retainedAmountFcfa", outcome.retainedAmount(),
                        "refundStatus", refund.status().name()));
        log.info("Resultat remboursement reservation {} : {} ({})", booking.getId(), refund.status(), refund.message());

        Map<String, Object> payload = Map.of("bookingId", booking.getId().toString(), "tripId", trip.getId().toString(),
                "refundAmountFcfa", outcome.refundAmount(), "retainedAmountFcfa", outcome.retainedAmount(),
                "seats", booking.getSeats(), "cancelledBy", "PASSENGER",
                "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                "departureAt", java.util.Objects.toString(trip.getDepartureAt(), ""));
        notificationService.notify(booking.getPassenger(), NotificationType.BOOKING_CANCELLED, payload);
        if (wasConfirmed) {
            String summary = "Ekuiseo : " + booking.getPassenger().getFirstName() + " a annule sa reservation ("
                    + booking.getSeats() + " place(s)) sur votre trajet " + trip.getOriginLabel() + " - "
                    + trip.getDestLabel() + " du " + formatLocal(trip.getDepartureAt()) + ".";
            if (driverCancellationPolicy.isLate(now, trip.getDepartureAt())) {
                notificationService.notifyCritical(trip.getDriver(), NotificationType.BOOKING_CANCELLED, payload, summary);
            } else {
                notificationService.notify(trip.getDriver(), NotificationType.BOOKING_CANCELLED, payload);
            }
        }
        return bookingMapper.toResponse(booking);
    }

    /**
     * Reservations d un trajet, pour son conducteur (GET /api/v1/trips/{id}/bookings) :
     * les reservations actives, terminees ou signalees absentes ; les annulations et les
     * acomptes jamais payes n interessent pas le depart.
     */
    @Transactional(readOnly = true)
    public List<bj.ekuiseo.api.dto.booking.TripBookingResponse> listForDriver(UUID tripId, UUID driverId) {
        Trip trip = tripRepository.findById(tripId).orElseThrow(() -> new NotFoundException("Trajet introuvable"));
        if (!trip.getDriver().getId().equals(driverId)) {
            throw new ForbiddenException("Vous n etes pas le conducteur de ce trajet");
        }
        return bookingRepository.findByTripIdAndStatusIn(tripId,
                        List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED, BookingStatus.NO_SHOW)).stream()
                .sorted(Comparator.comparing(Booking::getCreatedAt))
                .map(b -> new bj.ekuiseo.api.dto.booking.TripBookingResponse(b.getId(), b.getPassenger().getId(),
                        b.getPassenger().getFirstName(), b.getPassenger().getLastName(), b.getPassenger().getPhotoUrl(),
                        b.getPassenger().getRatingAvg(), b.getSeats(), b.getStatus(), b.getPaymentMethod(),
                        b.getBalanceDueOnBoard(), b.getPickupStopId(), b.getDropoffStopId(), b.getCreatedAt()))
                .toList();
    }

    /** Fenetre pendant laquelle le conducteur peut signaler l absence d un passager apres le depart. */
    static final java.time.Duration NO_SHOW_WINDOW = java.time.Duration.ofHours(48);

    /**
     * Signalement d absence par le conducteur (POST /api/v1/bookings/{id}/no-show,
     * constat F037) : la reservation confirmee d un passager qui ne s est pas presente
     * au depart passe NO_SHOW. L acompte reste acquis (bareme n.7 : 100 % retenus
     * apres l heure de depart) et est reverse net au conducteur comme un trajet
     * effectue. Possible entre l heure de depart et 48 h apres.
     */
    @Transactional
    public BookingResponse markNoShow(UUID id, UUID driverId) {
        Booking booking = findBooking(id);
        Trip trip = booking.getTrip();
        if (!trip.getDriver().getId().equals(driverId)) {
            throw new ForbiddenException("Vous n etes pas le conducteur de ce trajet");
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED && booking.getStatus() != BookingStatus.COMPLETED) {
            throw new BadRequestException("Seule une reservation confirmee peut etre signalee absente");
        }
        Instant now = Instant.now();
        if (now.isBefore(trip.getDepartureAt())) {
            throw new BadRequestException("L absence ne peut etre signalee qu apres l heure de depart");
        }
        if (now.isAfter(trip.getDepartureAt().plus(NO_SHOW_WINDOW))) {
            throw new BadRequestException("Le delai de signalement (48 h apres le depart) est depasse");
        }
        booking.setStatus(BookingStatus.NO_SHOW);
        bookingRepository.save(booking);
        auditService.log(driverId, "BOOKING_NO_SHOW", "booking", booking.getId(),
                Map.of("tripId", trip.getId().toString(), "retainedAmountFcfa", booking.getDepositAmount()));
        notificationService.notify(booking.getPassenger(), NotificationType.BOOKING_NO_SHOW,
                Map.of("bookingId", booking.getId().toString(), "tripId", trip.getId().toString(),
                        "retainedAmountFcfa", booking.getDepositAmount()));
        return bookingMapper.toResponse(booking);
    }

    /**
     * Annulation en cascade des reservations d un trajet annule par son conducteur
     * (regle metier n.6bis, ajoutee). Le remboursement passager est TOUJOURS integral
     * ici (ce n est jamais la faute du passager, contrairement a
     * {@link #cancelByPassenger}) - integral de depositAmount, seul montant reellement
     * encaisse par la plateforme (regle metier n.21) : balanceDueOnBoard n a jamais
     * transite par Kkiapay, le passager ne le doit simplement plus. L annulation est
     * comptabilisee dans les statistiques du conducteur si elle est tardive (voir
     * DriverCancellationPolicy), afin de pouvoir moderer les conducteurs peu fiables.
     */
    @Transactional
    public void cascadeCancelForDriverTripCancellation(Trip trip) {
        cascadeCancelTrip(trip, true, "ANNULATION_CONDUCTEUR", "TRIP_CANCELLED_BY_DRIVER");
    }

    /**
     * Cascade d une annulation de trajet decidee par la plateforme (suspension du
     * conducteur, moderation) : memes remboursements et notifications, sans compter
     * d annulation tardive au conducteur.
     */
    @Transactional
    public void cascadeCancelForPlatform(Trip trip, String reason) {
        cascadeCancelTrip(trip, false, reason, "TRIP_CANCELLED_BY_PLATFORM");
    }

    private void cascadeCancelTrip(Trip trip, boolean countLate, String refundReason, String auditAction) {
        List<Booking> active = bookingRepository.findByTripIdAndStatusIn(trip.getId(), ACTIVE_STATUSES);
        Instant now = Instant.now();
        boolean late = driverCancellationPolicy.isLate(now, trip.getDepartureAt());

        for (Booking booking : active) {
            booking.setStatus(BookingStatus.CANCELLED_BY_DRIVER);
            booking.setExpiresAt(null);
            bookingRepository.save(booking);

            PaymentService.RefundOutcome refund = paymentService.refundBooking(booking, booking.getDepositAmount(), refundReason);
            log.info("Annulation de trajet : reservation {} annulee, remboursement {} ({})",
                    booking.getId(), refund.status(), refund.message());

            notificationService.notifyCritical(booking.getPassenger(), NotificationType.BOOKING_CANCELLED,
                    Map.of("bookingId", booking.getId().toString(), "tripId", trip.getId().toString(),
                            "refundAmountFcfa", booking.getDepositAmount(),
                            "cancelledBy", "SUSPENSION_CONDUCTEUR".equals(refundReason) ? "PLATFORM" : "DRIVER",
                            "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                            "departureAt", java.util.Objects.toString(trip.getDepartureAt(), "")),
                    "Ekuiseo : votre trajet " + trip.getOriginLabel() + " - " + trip.getDestLabel() + " du "
                            + formatLocal(trip.getDepartureAt()) + " a ete annule par le conducteur. "
                            + (booking.getDepositAmount() > 0 ? "Votre acompte vous sera rembourse integralement." : ""));
        }

        if (countLate && !active.isEmpty() && late) {
            User driver = trip.getDriver();
            driver.setLateCancellationsCount(driver.getLateCancellationsCount() + 1);
            userRepository.save(driver);
            log.warn("Annulation tardive du conducteur {} pour le trajet {} ({} reservation(s) affectee(s))",
                    driver.getId(), trip.getId(), active.size());
        }

        auditService.log(trip.getDriver().getId(), auditAction, "trip", trip.getId(),
                Map.of("affectedBookings", active.size(), "late", late));
    }

    /**
     * Annulation des reservations actives d un passager suspendu (cascade de
     * suspension, constat F039) : places liberees, acompte rembourse integralement,
     * conducteur prevenu.
     */
    @Transactional
    public int cancelActiveBookingsForSuspendedPassenger(UUID passengerId) {
        List<Booking> active = bookingRepository.findByPassengerIdAndStatusIn(passengerId, ACTIVE_STATUSES);
        int cancelled = 0;
        for (Booking booking : active) {
            Trip trip = booking.getTrip();
            if (trip.getStatus() == TripStatus.ONGOING || trip.getStatus() == TripStatus.COMPLETED
                    || !Instant.now().isBefore(trip.getDepartureAt())) {
                continue;
            }
            boolean wasConfirmed = booking.getStatus() == BookingStatus.CONFIRMED;
            booking.setStatus(BookingStatus.CANCELLED_BY_PASSENGER);
            booking.setExpiresAt(null);
            bookingRepository.save(booking);
            releaseSeats(trip.getId(), booking.getSeats());
            PaymentService.RefundOutcome refund = paymentService.refundBooking(booking, booking.getDepositAmount(), "SUSPENSION_PASSAGER");
            log.info("Suspension : reservation {} annulee, remboursement {} ({})", booking.getId(), refund.status(), refund.message());
            if (wasConfirmed) {
                notificationService.notify(trip.getDriver(), NotificationType.BOOKING_CANCELLED,
                        Map.of("bookingId", booking.getId().toString(), "tripId", trip.getId().toString(),
                                "seats", booking.getSeats(), "cancelledBy", "PASSENGER",
                                "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                                "departureAt", java.util.Objects.toString(trip.getDepartureAt(), "")));
            }
            cancelled++;
        }
        return cancelled;
    }

    /** Date et heure locales du Benin pour les SMS et messages (ex. « 12/09/2026 07:30 »). */
    public static String formatLocal(Instant instant) {
        return java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(Tz.BENIN).format(instant);
    }

    /**
     * Expire les reservations PENDING_PAYMENT non payees depuis plus de
     * ekuiseo.booking.pending-payment-ttl-minutes (regle metier n.2), appele par
     * {@link BookingExpiryScheduler}.
     */
    @Transactional
    public int expireStalePendingBookings() {
        List<Booking> stale = bookingRepository.findExpirable(BookingStatus.PENDING_PAYMENT, Instant.now());
        for (Booking b : stale) {
            b.setStatus(BookingStatus.CANCELLED_BY_PASSENGER);
            bookingRepository.save(b);
            releaseSeats(b.getTrip().getId(), b.getSeats());
            log.info("Reservation {} expiree (paiement non recu sous {} min), places liberees",
                    b.getId(), pendingPaymentTtlMinutes);
        }
        return stale.size();
    }

    /** Restitue des places au trajet et le repasse en PUBLISHED s'il etait FULL (jamais s'il est CANCELLED/COMPLETED). */
    private void releaseSeats(UUID tripId, int seats) {
        tripRepository.incrementSeats(tripId, seats);
        Trip trip = tripRepository.findById(tripId).orElseThrow();
        if (trip.getStatus() == TripStatus.FULL) {
            tripRepository.updateStatus(tripId, TripStatus.PUBLISHED);
        }
    }

    void assertParticipant(Booking booking, UUID userId) {
        boolean isPassenger = booking.getPassenger().getId().equals(userId);
        boolean isDriver = booking.getTrip().getDriver().getId().equals(userId);
        if (!isPassenger && !isDriver) {
            throw new ForbiddenException("Vous n'etes pas autorise a consulter cette reservation");
        }
    }

    Booking findBooking(UUID id) {
        return bookingRepository.findById(id).orElseThrow(() -> new NotFoundException("Reservation introuvable"));
    }
}
