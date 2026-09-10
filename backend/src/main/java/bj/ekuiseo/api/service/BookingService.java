package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.common.FeePolicy;
import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Report;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripStop;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NoShowResolution;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.PassengerConfirmation;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.ReportReason;
import bj.ekuiseo.api.domain.enums.ReportStatus;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.dto.booking.BookingDetailResponse;
import bj.ekuiseo.api.dto.booking.BookingQuoteRequest;
import bj.ekuiseo.api.dto.booking.BookingResponse;
import bj.ekuiseo.api.dto.booking.ContestNoShowRequest;
import bj.ekuiseo.api.dto.booking.CreateBookingRequest;
import bj.ekuiseo.api.dto.booking.TripBookingResponse;
import bj.ekuiseo.api.dto.payment.PaymentPlanResponse;
import bj.ekuiseo.api.dto.trip.RecurringTripResponse;
import bj.ekuiseo.api.mapper.BookingMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverSubscriptionRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.ReportRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    /** Une reservation qui bloque des places : acompte attendu, accord du conducteur attendu (V19), ou confirmee. */
    static final List<BookingStatus> ACTIVE_STATUSES =
            List.of(BookingStatus.PENDING_PAYMENT, BookingStatus.PENDING_DRIVER_APPROVAL, BookingStatus.CONFIRMED);
    /** Motif de remboursement d un refus explicite du conducteur (V19). */
    static final String REFUND_REASON_DECLINED = "REFUS_CONDUCTEUR";
    /** Motif de remboursement d une demande restee sans reponse dans le delai (V19). */
    static final String REFUND_REASON_TIMED_OUT = "DELAI_ACCORD_CONDUCTEUR";
    /** Motif de remboursement d un acompte apres declaration d un conducteur absent (V25). */
    static final String REFUND_REASON_DRIVER_NO_SHOW = "CONDUCTEUR_ABSENT";
    /** Point n.13 de l audit : le paiement en especes contourne l acompte, il est reserve aux conducteurs a identite verifiee. */
    static final String CASH_REQUIRES_VERIFIED_DRIVER =
            "Le paiement en especes n est possible qu avec un conducteur dont l identite est verifiee";

    private final BookingRepository bookingRepository;
    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final UserRepository userRepository;
    private final DriverSubscriptionRepository driverSubscriptionRepository;
    private final MessageRepository messageRepository;
    private final ReviewRepository reviewRepository;
    private final ReportRepository reportRepository;
    private final BookingMapper bookingMapper;
    private final CancellationPolicy cancellationPolicy;
    private final DriverCancellationPolicy driverCancellationPolicy;
    private final NotificationService notificationService;
    private final PaymentService paymentService;
    private final AuditService auditService;
    private final FeePolicy feePolicy;
    private final DriverApprovalPolicy driverApprovalPolicy;
    private final int pendingPaymentTtlMinutes;
    /** Fenetre pendant laquelle le conducteur declare absent peut contester avant le remboursement automatique (V25). */
    private final Duration driverNoShowContestWindow;

    public BookingService(BookingRepository bookingRepository, TripRepository tripRepository,
                           TripStopRepository tripStopRepository, UserRepository userRepository, DriverSubscriptionRepository driverSubscriptionRepository,
                           MessageRepository messageRepository, ReviewRepository reviewRepository, ReportRepository reportRepository,
                           BookingMapper bookingMapper,
                           CancellationPolicy cancellationPolicy, DriverCancellationPolicy driverCancellationPolicy,
                           NotificationService notificationService, PaymentService paymentService,
                           AuditService auditService, FeePolicy feePolicy, DriverApprovalPolicy driverApprovalPolicy,
                           @Value("${ekuiseo.booking.pending-payment-ttl-minutes:20}") int pendingPaymentTtlMinutes,
                           @Value("${ekuiseo.booking.driver-no-show-contest-hours:24}") long driverNoShowContestHours) {
        this.bookingRepository = bookingRepository;
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.userRepository = userRepository;
        this.driverSubscriptionRepository = driverSubscriptionRepository;
        this.messageRepository = messageRepository;
        this.reviewRepository = reviewRepository;
        this.reportRepository = reportRepository;
        this.bookingMapper = bookingMapper;
        this.cancellationPolicy = cancellationPolicy;
        this.driverCancellationPolicy = driverCancellationPolicy;
        this.notificationService = notificationService;
        this.paymentService = paymentService;
        this.auditService = auditService;
        this.feePolicy = feePolicy;
        this.driverApprovalPolicy = driverApprovalPolicy;
        this.pendingPaymentTtlMinutes = pendingPaymentTtlMinutes;
        this.driverNoShowContestWindow = Duration.ofHours(driverNoShowContestHours);
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
        PaymentMethod method = resolvePaymentMethod(req.paymentMode());
        assertCashAllowed(trip, method);
        // Arrets et prix du troncon resolus AVANT de decrementer les places : un arret inconnu
        // ne doit pas laisser une place bloquee (constat F122).
        long unitPrice = resolveUnitPrice(trip, req.pickupStopId(), req.dropoffStopId());
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
        BookingAmounts amounts = computeAmounts(unitPrice, req.seats(), commissionWaived, method);
        boolean isCash = method == PaymentMethod.CASH;
        // Trajet a accord conducteur (V19, point n.13) : rien n est confirme sans le conducteur.
        // En mobile money, c est PaymentService#handleBookingPaymentResult qui pose
        // PENDING_DRIVER_APPROVAL une fois l acompte encaisse ; en especes, tout de suite.
        boolean awaitingDriver = isCash && !trip.isInstantBooking();
        Instant now = Instant.now();

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
                // Le paiement especes est confirme immediatement (regle au comptant a bord) sur un
                // trajet a reservation immediate, et n est ouvert qu aux conducteurs a identite
                // verifiee (assertCashAllowed, point n.13 de l audit). Le paiement mobile money
                // (acompte ou totalite selon le mode) reste PENDING_PAYMENT jusqu'au webhook Kkiapay
                // confirmant l'encaissement de deposit_amount.
                .status(isCash ? (awaitingDriver ? BookingStatus.PENDING_DRIVER_APPROVAL : BookingStatus.CONFIRMED)
                        : BookingStatus.PENDING_PAYMENT)
                // Echeance de l acompte (V12) : le scheduler d expiration lit cette colonne, que
                // PaymentService#initiate prolonge si le paiement est lance juste avant la limite.
                .expiresAt(isCash ? null : now.plus(pendingPaymentTtlMinutes, ChronoUnit.MINUTES))
                .approvalDeadlineAt(awaitingDriver ? driverApprovalPolicy.deadline(now, trip.getDepartureAt()) : null)
                .build();
        booking = bookingRepository.save(booking);

        if (awaitingDriver) {
            Map<String, Object> payload = NotificationTemplates.payload("bookingId", booking.getId().toString(),
                    "tripId", trip.getId().toString(), "passengerName", passenger.getFirstName(),
                    "seats", booking.getSeats(), "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                    "departureAt", Objects.toString(trip.getDepartureAt(), ""),
                    "approvalDeadlineAt", Objects.toString(booking.getApprovalDeadlineAt(), ""));
            notificationService.notifyCritical(trip.getDriver(), NotificationType.BOOKING_REQUESTED, payload);
            Map<String, Object> passengerPayload = new LinkedHashMap<>(payload);
            passengerPayload.put("forPassenger", true);
            notificationService.notify(passenger, NotificationType.BOOKING_REQUESTED, passengerPayload);
        } else if (isCash) {
            notificationService.notify(trip.getDriver(), NotificationType.BOOKING_CONFIRMED,
                    NotificationTemplates.payload("bookingId", booking.getId().toString(), "tripId", trip.getId().toString(),
                            "passengerName", booking.getPassenger().getFirstName(), "seats", booking.getSeats(),
                            "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                            "departureAt", Objects.toString(trip.getDepartureAt(), "")));
            // Constat F134 : le passager recoit lui aussi la confirmation (critique : c est son
            // billet), avec le montant a regler a bord.
            notificationService.notifyCritical(passenger, NotificationType.BOOKING_CONFIRMED,
                    NotificationTemplates.payload("bookingId", booking.getId().toString(), "tripId", trip.getId().toString(),
                            "seats", booking.getSeats(), "balanceDueOnBoardFcfa", booking.getBalanceDueOnBoard(),
                            "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                            "departureAt", Objects.toString(trip.getDepartureAt(), ""), "forPassenger", true),
                    "Ekuiseo : reservation confirmee, " + booking.getSeats() + " place(s) " + trip.getOriginLabel() + " - "
                            + trip.getDestLabel() + " le " + formatLocal(trip.getDepartureAt()) + ". A regler a bord : "
                            + booking.getBalanceDueOnBoard() + " F.");
        }
        return bookingMapper.toResponse(booking);
    }

    /** Point n.13 de l audit : CASH refuse (400) si le conducteur n a pas l identite verifiee. */
    private static void assertCashAllowed(Trip trip, PaymentMethod method) {
        if (method == PaymentMethod.CASH && !trip.getDriver().isIdentityVerified()) {
            throw new BadRequestException(CASH_REQUIRES_VERIFIED_DRIVER);
        }
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
        assertCashAllowed(trip, method);
        BookingAmounts amounts = computeAmounts(resolveUnitPrice(trip, req.pickupStopId(), req.dropoffStopId()),
                req.seats(), commissionWaived, method);
        boolean isCash = method == PaymentMethod.CASH;
        // Aucune reservation n'existe encore : pas de createdAt reel pour ancrer l'echeance
        // de l'acompte, Instant.now() sert d'estimation "si vous reservez maintenant" -
        // recalculee exactement au moment de la reservation reelle (voir buildPaymentPlan).
        Instant depositDueAt = isCash ? null : Instant.now().plus(pendingPaymentTtlMinutes, ChronoUnit.MINUTES);
        return new PaymentPlanResponse(amounts.amount(), amounts.depositAmount(), amounts.balanceDueOnBoard(),
                amounts.serviceFee(), method, "PENDING", depositDueAt,
                (int) CancellationPolicy.FREE_CANCELLATION_WINDOW.toHours(), null);
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
        if (trip.getDriver().getStatus() != UserStatus.ACTIVE) {
            throw new ConflictException("Ce conducteur n est plus disponible");
        }
    }

    /**
     * Prix unitaire de la reservation (tarif par troncon, constat F122) : difference des
     * {@code price_from_origin} entre l arret de descente (prix du trajet complet si absent :
     * la destination) et l arret de montee (0 si absent : l origine). Les deux arrets doivent
     * appartenir au trajet ({@link #resolveStop}) et la montee preceder la descente.
     */
    private long resolveUnitPrice(Trip trip, UUID pickupStopId, UUID dropoffStopId) {
        if (pickupStopId == null && dropoffStopId == null) {
            return trip.getPricePerSeat();
        }
        List<TripStop> stops = tripStopRepository.findByTripIdOrderByPosition(trip.getId());
        TripStop pickup = resolveStop(stops, pickupStopId);
        TripStop dropoff = resolveStop(stops, dropoffStopId);
        int pickupPosition = pickup == null ? 0 : pickup.getPosition();
        int dropoffPosition = dropoff == null ? Integer.MAX_VALUE : dropoff.getPosition();
        if (pickupPosition >= dropoffPosition) {
            throw new BadRequestException("L arret de montee doit preceder l arret de descente");
        }
        long pickupPrice = pickup == null ? 0L : pickup.getPriceFromOrigin();
        long dropoffPrice = dropoff == null ? trip.getPricePerSeat() : dropoff.getPriceFromOrigin();
        return Math.max(0L, dropoffPrice - pickupPrice);
    }

    /** Arret du trajet portant cet identifiant ; null pour un identifiant absent ; 400 pour un arret d un autre trajet. */
    private static TripStop resolveStop(List<TripStop> stops, UUID stopId) {
        if (stopId == null) {
            return null;
        }
        return stops.stream()
                .filter(stop -> stopId.equals(stop.getId()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Arret inconnu pour ce trajet"));
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
                        vehicle.getComfortLevel(), vehicle.getVehicleType()));
        return new BookingDetailResponse(booking.getId(), trip.getId(), booking.getPassenger().getId(),
                booking.getSeats(), booking.getAmount(), booking.getServiceFee(), booking.getStatus(),
                booking.getPaymentMethod(), booking.getCreatedAt(), booking.getPickupStopId(), booking.getDropoffStopId(),
                buildPaymentPlan(booking), tripSummary, unread,
                reviewRepository.existsByTripIdAndAuthorIdAndTargetId(trip.getId(), requesterId, driver.getId()),
                booking.getPassengerConfirmation(), booking.getPassengerConfirmedAt(),
                paymentService.refundSummary(booking.getId()).orElse(null),
                booking.getDriverNoShowRefundDueAt(), booking.getDriverNoShowContestedAt(),
                booking.getDriverNoShowResolution(), booking.getDriverNoShowResolvedAt());
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
        Instant approvalDeadlineAt = booking.getStatus() == BookingStatus.PENDING_DRIVER_APPROVAL
                ? booking.getApprovalDeadlineAt() : null;
        return new PaymentPlanResponse(booking.getAmount(), booking.getDepositAmount(), booking.getBalanceDueOnBoard(),
                booking.getServiceFee(), booking.getPaymentMethod(), paymentPlanStatus(booking), depositDueAt,
                (int) CancellationPolicy.FREE_CANCELLATION_WINDOW.toHours(), approvalDeadlineAt);
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
        if (status == BookingStatus.EXPIRED) {
            return "EXPIRED";
        }
        if (status == BookingStatus.PENDING_PAYMENT) {
            return "PENDING";
        }
        if (status == BookingStatus.PENDING_DRIVER_APPROVAL) {
            // Acompte encaisse (ou especes), place bloquee, mais rien de confirme sans le conducteur (V19).
            return "AWAITING_DRIVER";
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
            if (b.getStatus() == BookingStatus.CANCELLED_BY_PASSENGER || b.getStatus() == BookingStatus.CANCELLED_BY_DRIVER
                    || b.getStatus() == BookingStatus.EXPIRED) {
                continue; // une reservation jamais payee n est pas une habitude de trajet
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
                    trip.getOriginLabel(), trip.getDestLabel(), TripStatus.PUBLISHED, now, 0);
            var next = tripRepository.findFirstByOriginLabelAndDestLabelAndStatusAndDepartureAtAfterAndSeatsAvailableGreaterThanOrderByDepartureAtAsc(
                    trip.getOriginLabel(), trip.getDestLabel(), TripStatus.PUBLISHED, now, 0);
            UUID virtualId = UUID.nameUUIDFromBytes(
                    (passengerId + "|" + trip.getOriginLabel() + "|" + trip.getDestLabel()).getBytes());
            result.add(new RecurringTripResponse(virtualId, trip.getOriginLabel(), trip.getOriginLat(), trip.getOriginLng(),
                    trip.getDestLabel(), trip.getDestLat(), trip.getDestLng(),
                    weekdays.stream().map(DayOfWeek::getValue).sorted().toList(),
                    timeOfDay.toString(), mostRecent.getSeats(), matches,
                    next.map(Trip::getDepartureAt).orElse(null)));
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
        if (!ACTIVE_STATUSES.contains(booking.getStatus())) {
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
        if (booking.getStatus() == BookingStatus.PENDING_DRIVER_APPROVAL) {
            // Le conducteur n a pas encore repondu (V19) : le passager n est engage a rien.
            outcome = new CancellationPolicy.Outcome(booking.getDepositAmount(), 0L,
                    "Annulation gratuite (demande en attente de l accord du conducteur)");
        } else if (booking.getFreeCancellationUntil() != null && now.isBefore(booking.getFreeCancellationUntil())) {
            // Le conducteur a modifie l horaire : annulation gratuite pendant 24 h (lot 1.3).
            outcome = new CancellationPolicy.Outcome(booking.getDepositAmount(), 0L,
                    "Annulation gratuite (horaire modifie par le conducteur)");
        } else {
            outcome = cancellationPolicy.evaluate(booking.getDepositAmount(), now, trip.getDepartureAt());
        }
        log.info("Annulation reservation {} : remboursement={} retenu={} ({})",
                booking.getId(), outcome.refundAmount(), outcome.retainedAmount(), outcome.reason());

        // Le conducteur n est prevenu que si la place lui etait deja acquise ou demandee (V19) :
        // un acompte jamais paye ne l a jamais concerne.
        boolean wasConfirmed = booking.getStatus() != BookingStatus.PENDING_PAYMENT;
        booking.setStatus(BookingStatus.CANCELLED_BY_PASSENGER);
        booking.setExpiresAt(null);
        booking.setApprovalDeadlineAt(null);
        bookingRepository.save(booking);
        releaseSeats(trip.getId(), booking.getSeats());

        PaymentService.RefundOutcome refund = paymentService.refundBooking(booking, outcome.refundAmount(), "ANNULATION_PASSAGER");
        auditService.log(passengerId, "BOOKING_CANCELLED_BY_PASSENGER", "booking", booking.getId(),
                Map.of("refundAmountFcfa", outcome.refundAmount(), "retainedAmountFcfa", outcome.retainedAmount(),
                        "refundStatus", refund.status().name()));
        log.info("Resultat remboursement reservation {} : {} ({})", booking.getId(), refund.status(), refund.message());

        // refundStatus (constat F037) : MANUAL_REQUIRED fait mentionner le delai de 5 jours
        // ouvres dans le gabarit (NotificationTemplates#refundLine).
        Map<String, Object> payload = NotificationTemplates.payload("bookingId", booking.getId().toString(),
                "tripId", trip.getId().toString(),
                "refundAmountFcfa", outcome.refundAmount(), "retainedAmountFcfa", outcome.retainedAmount(),
                "refundStatus", refund.status().name(),
                "seats", booking.getSeats(), "cancelledBy", "PASSENGER",
                "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                "departureAt", Objects.toString(trip.getDepartureAt(), ""));
        // Le passager recoit un accuse de reception avec le sort de son acompte (forPassenger
        // distingue le gabarit de celui envoye au conducteur, meme type et memes montants).
        Map<String, Object> passengerPayload = new LinkedHashMap<>(payload);
        passengerPayload.put("forPassenger", true);
        notificationService.notify(booking.getPassenger(), NotificationType.BOOKING_CANCELLED, passengerPayload);
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
     * les demandes en attente de sa reponse (V19), les reservations confirmees, terminees ou
     * signalees absentes ; les annulations et les acomptes jamais payes n interessent pas le depart.
     */
    @Transactional(readOnly = true)
    public List<TripBookingResponse> listForDriver(UUID tripId, UUID driverId) {
        Trip trip = tripRepository.findById(tripId).orElseThrow(() -> new NotFoundException("Trajet introuvable"));
        if (!trip.getDriver().getId().equals(driverId)) {
            throw new ForbiddenException("Vous n etes pas le conducteur de ce trajet");
        }
        return bookingRepository.findByTripIdAndStatusIn(tripId,
                        List.of(BookingStatus.PENDING_DRIVER_APPROVAL, BookingStatus.CONFIRMED,
                                BookingStatus.COMPLETED, BookingStatus.NO_SHOW, BookingStatus.DRIVER_NO_SHOW)).stream()
                .sorted(Comparator.comparing(Booking::getCreatedAt))
                .map(b -> new TripBookingResponse(b.getId(), b.getPassenger().getId(),
                        b.getPassenger().getFirstName(), b.getPassenger().getLastName(), b.getPassenger().getPhotoUrl(),
                        b.getPassenger().getRatingAvg(), b.getSeats(), b.getStatus(), b.getPaymentMethod(),
                        b.getBalanceDueOnBoard(), b.getPickupStopId(), b.getDropoffStopId(), b.getCreatedAt(),
                        b.getStatus() == BookingStatus.PENDING_DRIVER_APPROVAL ? b.getApprovalDeadlineAt() : null,
                        b.getDriverNoShowRefundDueAt(), b.getDriverNoShowContestedAt(), b.getDriverNoShowResolution()))
                .toList();
    }

    /**
     * Accord du conducteur sur une demande (POST /api/v1/bookings/{id}/accept, V19) : la
     * reservation passe CONFIRMED, le passager est prevenu (critique : c est son billet).
     * Refuse une fois le trajet parti : le cycle de vie a alors deja tranche.
     */
    @Transactional
    public BookingResponse acceptByDriver(UUID id, UUID driverId) {
        Booking booking = findBooking(id);
        Trip trip = booking.getTrip();
        assertDriverDecision(booking, driverId);
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setApprovalDeadlineAt(null);
        bookingRepository.save(booking);
        auditService.log(driverId, "BOOKING_ACCEPTED_BY_DRIVER", "booking", booking.getId(),
                Map.of("tripId", trip.getId().toString(), "seats", booking.getSeats()));
        notificationService.notifyCritical(booking.getPassenger(), NotificationType.BOOKING_CONFIRMED,
                NotificationTemplates.payload("bookingId", booking.getId().toString(), "tripId", trip.getId().toString(),
                        "seats", booking.getSeats(), "balanceDueOnBoardFcfa", booking.getBalanceDueOnBoard(),
                        "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                        "departureAt", Objects.toString(trip.getDepartureAt(), ""), "forPassenger", true,
                        "acceptedByDriver", true),
                "Ekuiseo : le conducteur a accepte votre demande, " + booking.getSeats() + " place(s) "
                        + trip.getOriginLabel() + " - " + trip.getDestLabel() + " le " + formatLocal(trip.getDepartureAt())
                        + (booking.getBalanceDueOnBoard() > 0 ? ". A regler a bord : " + booking.getBalanceDueOnBoard() + " F." : "."));
        return bookingMapper.toResponse(booking);
    }

    /**
     * Refus du conducteur (POST /api/v1/bookings/{id}/decline, V19) : places liberees, acompte
     * rembourse INTEGRALEMENT (ce n est jamais la faute du passager), passager prevenu avec le
     * motif eventuel. Un refus n est pas une annulation tardive : rien n est compte au conducteur.
     */
    @Transactional
    public BookingResponse declineByDriver(UUID id, UUID driverId, String reason) {
        Booking booking = findBooking(id);
        assertDriverDecision(booking, driverId);
        String trimmed = reason == null ? "" : reason.trim();
        decline(booking, driverId, trimmed, false, REFUND_REASON_DECLINED, "BOOKING_DECLINED_BY_DRIVER");
        return bookingMapper.toResponse(booking);
    }

    /** Seul le conducteur du trajet decide, et seulement tant que la demande est en attente et le trajet pas parti. */
    private static void assertDriverDecision(Booking booking, UUID driverId) {
        Trip trip = booking.getTrip();
        if (!trip.getDriver().getId().equals(driverId)) {
            throw new ForbiddenException("Vous n etes pas le conducteur de ce trajet");
        }
        if (booking.getStatus() != BookingStatus.PENDING_DRIVER_APPROVAL) {
            throw new BadRequestException("Cette reservation n attend pas votre reponse");
        }
        if (trip.getStatus() == TripStatus.CANCELLED || !Instant.now().isBefore(trip.getDepartureAt())) {
            throw new BadRequestException("Le trajet est deja parti ou annule : la demande ne peut plus etre traitee");
        }
    }

    /**
     * Demandes restees sans reponse (echeance depassee, ou trajet parti : le cycle de vie ne
     * laisse pas une demande survivre au depart) : traitees comme un refus, avec remboursement
     * integral et notification « sans reponse du conducteur ». Appele par {@link BookingExpiryScheduler}.
     */
    @Transactional
    public int expireStaleApprovals() {
        List<Booking> stale = bookingRepository.findExpirableApprovals(Instant.now());
        for (Booking booking : stale) {
            decline(booking, null, "", true, REFUND_REASON_TIMED_OUT, "BOOKING_APPROVAL_TIMED_OUT");
            log.info("Demande {} sans reponse du conducteur dans le delai : traitee comme un refus", booking.getId());
        }
        return stale.size();
    }

    private void decline(Booking booking, UUID actorId, String reason, boolean timedOut, String refundReason, String auditAction) {
        // Trajet et passager lus AVANT releaseSeats (voir expireStalePendingBookings).
        Trip trip = booking.getTrip();
        User passenger = booking.getPassenger();
        booking.setStatus(BookingStatus.CANCELLED_BY_DRIVER);
        booking.setExpiresAt(null);
        booking.setApprovalDeadlineAt(null);
        bookingRepository.save(booking);
        releaseSeats(trip.getId(), booking.getSeats());

        long refundAmount = booking.getDepositAmount();
        PaymentService.RefundOutcome refund = paymentService.refundBooking(booking, refundAmount, refundReason);
        log.info("Refus de la demande {} ({}) : remboursement {} ({})", booking.getId(), refundReason,
                refund.status(), refund.message());
        auditService.log(actorId, auditAction, "booking", booking.getId(),
                Map.of("tripId", trip.getId().toString(), "seatsReleased", booking.getSeats(),
                        "refundAmountFcfa", refundAmount, "refundStatus", refund.status().name(),
                        "reason", reason));

        Map<String, Object> payload = NotificationTemplates.payload("bookingId", booking.getId().toString(),
                "tripId", trip.getId().toString(), "seats", booking.getSeats(),
                "refundAmountFcfa", refundAmount, "refundStatus", refund.status().name(),
                "timedOut", timedOut, "reason", reason.isEmpty() ? null : reason,
                "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                "departureAt", Objects.toString(trip.getDepartureAt(), ""));
        notificationService.notifyCritical(passenger, NotificationType.BOOKING_DECLINED, payload);
        if (timedOut) {
            // Le conducteur apprend qu une demande lui a echappe : in-app suffit.
            Map<String, Object> driverPayload = new LinkedHashMap<>(payload);
            driverPayload.put("forDriver", true);
            driverPayload.put("passengerName", passenger.getFirstName());
            notificationService.notify(trip.getDriver(), NotificationType.BOOKING_DECLINED, driverPayload);
        }
    }

    /** Fenetre pendant laquelle le conducteur peut signaler l absence d un passager apres le depart. */
    static final Duration NO_SHOW_WINDOW = Duration.ofHours(48);
    /**
     * Fenetre du constat passager (V21) : jusqu a l eligibilite au reversement (24 h apres le
     * depart, PayoutService). Passe ce delai, la confirmation est tacite.
     */
    static final Duration PASSENGER_CONFIRMATION_WINDOW = Duration.ofHours(24);

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
     * Le passager confirme que le trajet a eu lieu (V21). Ouvert des l heure de depart, sur une
     * reservation honoree (CONFIRMED ou COMPLETED) sans constat prealable. Sans reponse dans les
     * {@link #PASSENGER_CONFIRMATION_WINDOW}, la confirmation est tacite : rien ne change.
     */
    @Transactional
    public BookingResponse confirmTripDone(UUID id, UUID passengerId) {
        Booking booking = findBooking(id);
        requirePassengerConfirmable(booking, passengerId);
        booking.setPassengerConfirmation(PassengerConfirmation.TRIP_DONE);
        booking.setPassengerConfirmedAt(Instant.now());
        bookingRepository.save(booking);
        auditService.log(passengerId, "BOOKING_TRIP_CONFIRMED", "booking", booking.getId(),
                Map.of("tripId", booking.getTrip().getId().toString()));
        return bookingMapper.toResponse(booking);
    }

    /**
     * Le passager declare que le conducteur n est pas venu (V21), entre l heure de depart et
     * {@link #PASSENGER_CONFIRMATION_WINDOW} apres (au-dela, la reservation est eligible au
     * reversement et la confirmation tacite). La reservation passe DRIVER_NO_SHOW - elle sort
     * des reversements (PayoutService ne verse que CONFIRMED / COMPLETED / NO_SHOW) - et un
     * signalement NO_SHOW est ouvert pour la moderation. Le conducteur est prevenu (critique :
     * son argent est en jeu), sans les details du passager.
     *
     * <p>V25 : l acompte est rembourse <b>automatiquement</b> a l echeance de la fenetre de
     * contestation ({@code ekuiseo.booking.driver-no-show-contest-hours}, 24 h) si le conducteur
     * ne conteste pas ({@link #contestDriverNoShow}) ; la moderation peut trancher avant
     * ({@link #resolveDriverNoShow}).</p>
     */
    @Transactional
    public BookingResponse reportDriverNoShow(UUID id, UUID passengerId, String details) {
        Booking booking = findBooking(id);
        requirePassengerConfirmable(booking, passengerId);
        Trip trip = booking.getTrip();
        Instant now = Instant.now();
        if (now.isAfter(trip.getDepartureAt().plus(PASSENGER_CONFIRMATION_WINDOW))) {
            throw new BadRequestException("Le delai pour signaler l absence du conducteur (24 h apres le depart) est depasse");
        }
        Instant refundDueAt = now.plus(driverNoShowContestWindow);
        booking.setStatus(BookingStatus.DRIVER_NO_SHOW);
        booking.setPassengerConfirmation(PassengerConfirmation.DRIVER_NO_SHOW);
        booking.setPassengerConfirmedAt(now);
        booking.setDriverNoShowRefundDueAt(refundDueAt);
        bookingRepository.save(booking);

        String trimmed = details == null ? null : details.trim();
        Report report = reportRepository.save(Report.builder()
                .reporter(booking.getPassenger())
                .reportedTrip(trip)
                .bookingId(booking.getId())
                .reasonCode(ReportReason.NO_SHOW.name())
                .details(trimmed == null || trimmed.isEmpty()
                        ? "Le passager declare que le conducteur n est pas venu au depart."
                        : trimmed)
                .build());

        auditService.log(passengerId, "BOOKING_DRIVER_NO_SHOW", "booking", booking.getId(),
                Map.of("tripId", trip.getId().toString(), "reportId", report.getId().toString(),
                        "depositAmountFcfa", booking.getDepositAmount(), "refundDueAt", refundDueAt.toString()));
        notificationService.notifyCritical(trip.getDriver(), NotificationType.DRIVER_NO_SHOW_REPORTED,
                Map.of("bookingId", booking.getId().toString(), "tripId", trip.getId().toString(),
                        "reportId", report.getId().toString(),
                        "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                        "departureAt", trip.getDepartureAt().toString(),
                        "contestUntil", refundDueAt.toString(),
                        "depositAmountFcfa", booking.getDepositAmount()));
        return bookingMapper.toResponse(booking);
    }

    /**
     * Le conducteur conteste l absence declaree (V25) : le remboursement automatique est gele,
     * le signalement passe en examen et la moderation tranche avec les deux versions. Possible
     * tant que le dossier n est pas tranche - meme apres l echeance, si le scheduler n est pas
     * encore passe : c est la resolution qui fait foi, pas l heure.
     */
    @Transactional
    public BookingResponse contestDriverNoShow(UUID id, UUID driverId, ContestNoShowRequest req) {
        Booking booking = findBooking(id);
        Trip trip = booking.getTrip();
        if (!trip.getDriver().getId().equals(driverId)) {
            throw new ForbiddenException("Vous n etes pas le conducteur de ce trajet");
        }
        if (booking.getStatus() != BookingStatus.DRIVER_NO_SHOW) {
            throw new BadRequestException("Aucune absence declaree sur cette reservation");
        }
        if (booking.getDriverNoShowResolution() != null) {
            throw new ConflictException("Ce dossier est deja tranche : " + noShowResolutionLabel(booking.getDriverNoShowResolution()));
        }
        if (booking.getDriverNoShowContestedAt() != null) {
            throw new ConflictException("Vous avez deja conteste cette absence ; la moderation examine le dossier");
        }
        Instant now = Instant.now();
        booking.setDriverNoShowContestedAt(now);
        booking.setDriverNoShowContestDetails(req.details().trim());
        bookingRepository.save(booking);
        for (Report report : openNoShowReports(booking)) {
            if (report.getStatus() == ReportStatus.OPEN) {
                report.setStatus(ReportStatus.IN_REVIEW);
                reportRepository.save(report);
            }
        }
        auditService.log(driverId, "BOOKING_DRIVER_NO_SHOW_CONTESTED", "booking", booking.getId(),
                Map.of("tripId", trip.getId().toString(), "depositAmountFcfa", booking.getDepositAmount()));
        notificationService.notify(booking.getPassenger(), NotificationType.NO_SHOW_CONTESTED,
                Map.of("bookingId", booking.getId().toString(), "tripId", trip.getId().toString(),
                        "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                        "departureAt", trip.getDepartureAt().toString(),
                        "depositAmountFcfa", booking.getDepositAmount()));
        log.info("Reservation {} : absence du conducteur contestee, remboursement gele", booking.getId());
        return bookingMapper.toResponse(booking);
    }

    /**
     * Tranche un dossier « conducteur absent » (V25). {@code adminId} null = decision automatique
     * a l echeance de la fenetre de contestation (toujours REFUND_PASSENGER).
     * <ul>
     *   <li>REFUND_PASSENGER : l acompte encaisse est rembourse integralement (RefundService,
     *       Kkiapay hors transaction avec reprise) ; la reservation reste DRIVER_NO_SHOW, donc
     *       hors reversement.</li>
     *   <li>PAY_DRIVER : le trajet est repute effectue ; la reservation redevient COMPLETED et
     *       rejoint le prochain lot de reversement du conducteur.</li>
     * </ul>
     * Dans les deux cas le signalement lie est clos (RESOLVED, note conservee) et les deux
     * parties sont prevenues.
     */
    @Transactional
    public BookingResponse resolveDriverNoShow(UUID adminId, UUID id, NoShowResolution decision, String note) {
        Booking booking = findBooking(id);
        if (decision == null) {
            throw new BadRequestException("La decision est obligatoire");
        }
        if (booking.getStatus() != BookingStatus.DRIVER_NO_SHOW) {
            throw new BadRequestException("Aucune absence du conducteur declaree sur cette reservation");
        }
        if (booking.getDriverNoShowResolution() != null) {
            throw new ConflictException("Ce dossier est deja tranche : " + noShowResolutionLabel(booking.getDriverNoShowResolution()));
        }
        Trip trip = booking.getTrip();
        Instant now = Instant.now();
        String resolutionNote = note == null || note.isBlank() ? defaultNoShowNote(decision, adminId == null) : note.trim();
        booking.setDriverNoShowResolution(decision);
        booking.setDriverNoShowResolvedAt(now);
        booking.setDriverNoShowResolvedBy(adminId);
        String refundStatus = "NOT_APPLICABLE";
        if (decision == NoShowResolution.REFUND_PASSENGER) {
            PaymentService.RefundOutcome refund = paymentService.refundBooking(booking, booking.getDepositAmount(), REFUND_REASON_DRIVER_NO_SHOW);
            refundStatus = refund.status().name();
            log.info("Reservation {} : conducteur absent, remboursement de {} FCFA : {} ({})",
                    booking.getId(), booking.getDepositAmount(), refund.status(), refund.message());
        } else {
            // Trajet maintenu : la reservation redevient reversable (PayoutService#PAYABLE_STATUSES).
            booking.setStatus(BookingStatus.COMPLETED);
        }
        bookingRepository.save(booking);

        User admin = adminId == null ? null : userRepository.findById(adminId).orElse(null);
        for (Report report : openNoShowReports(booking)) {
            report.setStatus(ReportStatus.RESOLVED);
            report.setResolutionNote(resolutionNote);
            report.setResolvedBy(admin);
            report.setResolvedAt(now);
            reportRepository.save(report);
        }

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("tripId", trip.getId().toString());
        audit.put("decision", decision.name());
        audit.put("automatic", adminId == null);
        audit.put("depositAmountFcfa", booking.getDepositAmount());
        audit.put("refundStatus", refundStatus);
        audit.put("note", resolutionNote);
        auditService.log(adminId, "BOOKING_DRIVER_NO_SHOW_RESOLVED", "booking", booking.getId(), audit);

        Map<String, Object> payload = NotificationTemplates.payload("bookingId", booking.getId().toString(),
                "tripId", trip.getId().toString(), "decision", decision.name(), "automatic", adminId == null,
                "depositAmountFcfa", booking.getDepositAmount(), "refundStatus", refundStatus,
                "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                "departureAt", trip.getDepartureAt().toString());
        Map<String, Object> passengerPayload = new LinkedHashMap<>(payload);
        passengerPayload.put("forPassenger", true);
        notificationService.notify(booking.getPassenger(), NotificationType.NO_SHOW_DISPUTE_RESOLVED, passengerPayload);
        notificationService.notify(trip.getDriver(), NotificationType.NO_SHOW_DISPUTE_RESOLVED, payload);
        return bookingMapper.toResponse(booking);
    }

    /**
     * Identifiants des declarations « conducteur absent » dont la fenetre de contestation est
     * echue sans contestation ni decision (V25) : le scheduler les rembourse une par une, chacune
     * dans sa propre transaction (une reservation en erreur n en bloque pas une autre).
     */
    @Transactional(readOnly = true)
    public List<UUID> findDriverNoShowRefundsDue(Instant now) {
        return bookingRepository.findDriverNoShowRefundsDue(now);
    }

    private List<Report> openNoShowReports(Booking booking) {
        return reportRepository.findByBookingIdAndReasonCodeAndStatusIn(booking.getId(), ReportReason.NO_SHOW.name(),
                List.of(ReportStatus.OPEN, ReportStatus.IN_REVIEW));
    }

    private static String defaultNoShowNote(NoShowResolution decision, boolean automatic) {
        if (decision == NoShowResolution.REFUND_PASSENGER) {
            return automatic
                    ? "Remboursement automatique de l acompte : le conducteur n a pas conteste l absence dans le delai."
                    : "Absence du conducteur retenue : acompte rembourse au passager.";
        }
        return "Trajet maintenu : la reservation est reversee au conducteur.";
    }

    static String noShowResolutionLabel(NoShowResolution resolution) {
        return resolution == NoShowResolution.REFUND_PASSENGER ? "acompte rembourse au passager" : "trajet maintenu, conducteur paye";
    }

    /** Garde commune des deux constats du passager : sa reservation, honoree, apres le depart, sans constat prealable. */
    private void requirePassengerConfirmable(Booking booking, UUID passengerId) {
        if (!booking.getPassenger().getId().equals(passengerId)) {
            throw new ForbiddenException("Cette reservation n est pas la votre");
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED && booking.getStatus() != BookingStatus.COMPLETED) {
            throw new BadRequestException("Seule une reservation confirmee ou terminee peut faire l objet d un constat");
        }
        if (booking.getPassengerConfirmation() != PassengerConfirmation.PENDING) {
            throw new ConflictException("Vous avez deja donne votre constat pour ce trajet");
        }
        if (Instant.now().isBefore(booking.getTrip().getDepartureAt())) {
            throw new BadRequestException("Le constat ne peut etre donne qu apres l heure de depart");
        }
    }

    /**
     * Annulation en cascade des reservations d un trajet annule par son conducteur
     * (regle metier n.6bis, ajoutee). Le remboursement passager est TOUJOURS integral
     * ici (ce n est jamais la faute du passager, contrairement a
     * {@link #cancelByPassenger}) - integral de depositAmount, seul montant reellement
     * encaisse par la plateforme (regle metier n.21) : balanceDueOnBoard n a jamais
     * transite par Kkiapay, le passager ne le doit simplement plus. Une reservation encore
     * PENDING_PAYMENT n a rien encaisse : elle est annulee sans remboursement ni promesse
     * de remboursement (constat F144). L annulation est comptabilisee dans les statistiques
     * du conducteur si elle est tardive (voir DriverCancellationPolicy), afin de pouvoir
     * moderer les conducteurs peu fiables.
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
            // Seule une reservation confirmee, ou en attente de l accord du conducteur (V19 : acompte
            // deja encaisse), a un acompte a rembourser (constat F144) ; une reservation en attente
            // de paiement n a rien encaisse.
            boolean paid = booking.getStatus() != BookingStatus.PENDING_PAYMENT && booking.getDepositAmount() > 0;
            booking.setStatus(BookingStatus.CANCELLED_BY_DRIVER);
            booking.setExpiresAt(null);
            booking.setApprovalDeadlineAt(null);
            bookingRepository.save(booking);

            long refundAmount = paid ? booking.getDepositAmount() : 0L;
            if (paid) {
                PaymentService.RefundOutcome refund = paymentService.refundBooking(booking, refundAmount, refundReason);
                log.info("Annulation de trajet : reservation {} annulee, remboursement {} ({})",
                        booking.getId(), refund.status(), refund.message());
            } else {
                log.info("Annulation de trajet : reservation {} annulee sans acompte encaisse, rien a rembourser", booking.getId());
            }

            notificationService.notifyCritical(booking.getPassenger(), NotificationType.BOOKING_CANCELLED,
                    Map.of("bookingId", booking.getId().toString(), "tripId", trip.getId().toString(),
                            "refundAmountFcfa", refundAmount,
                            "cancelledBy", "SUSPENSION_CONDUCTEUR".equals(refundReason) ? "PLATFORM" : "DRIVER",
                            "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                            "departureAt", Objects.toString(trip.getDepartureAt(), "")),
                    "Ekuiseo : votre trajet " + trip.getOriginLabel() + " - " + trip.getDestLabel() + " du "
                            + formatLocal(trip.getDepartureAt()) + " a ete annule par le conducteur."
                            + (paid ? " Votre acompte vous sera rembourse integralement." : " Aucun montant n avait ete preleve."));
        }

        if (countLate && !active.isEmpty() && late) {
            // Increment atomique en base (constat F147) : deux cascades concurrentes ne se
            // perdent plus mutuellement une annulation.
            UUID driverId = trip.getDriver().getId();
            userRepository.incrementLateCancellations(driverId);
            log.warn("Annulation tardive du conducteur {} pour le trajet {} ({} reservation(s) affectee(s))",
                    driverId, trip.getId(), active.size());
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
            boolean wasConfirmed = booking.getStatus() != BookingStatus.PENDING_PAYMENT;
            booking.setStatus(BookingStatus.CANCELLED_BY_PASSENGER);
            booking.setExpiresAt(null);
            booking.setApprovalDeadlineAt(null);
            bookingRepository.save(booking);
            releaseSeats(trip.getId(), booking.getSeats());
            PaymentService.RefundOutcome refund = paymentService.refundBooking(booking, booking.getDepositAmount(), "SUSPENSION_PASSAGER");
            log.info("Suspension : reservation {} annulee, remboursement {} ({})", booking.getId(), refund.status(), refund.message());
            if (wasConfirmed) {
                notificationService.notify(trip.getDriver(), NotificationType.BOOKING_CANCELLED,
                        Map.of("bookingId", booking.getId().toString(), "tripId", trip.getId().toString(),
                                "seats", booking.getSeats(), "cancelledBy", "PASSENGER",
                                "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                                "departureAt", Objects.toString(trip.getDepartureAt(), "")));
            }
            cancelled++;
        }
        return cancelled;
    }

    /** Date et heure locales du Benin pour les SMS et messages (ex. « 12/09/2026 07:30 »). */
    public static String formatLocal(Instant instant) {
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(Tz.BENIN).format(instant);
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
            // Constats F010/F116/F232 : une expiration n est pas une annulation volontaire. Le
            // statut EXPIRED est journalise (acteur : le systeme) et le passager prevenu in-app.
            b.setStatus(BookingStatus.EXPIRED);
            bookingRepository.save(b);
            // Le trajet et le passager sont lus AVANT releaseSeats : incrementSeats vide le
            // contexte de persistance (clearAutomatically) et un proxy non initialise
            // leverait ensuite LazyInitializationException.
            Trip trip = b.getTrip();
            User passenger = b.getPassenger();
            Map<String, Object> payload = NotificationTemplates.payload("bookingId", b.getId().toString(),
                    "tripId", trip.getId().toString(), "seats", b.getSeats(), "ttlMinutes", pendingPaymentTtlMinutes,
                    "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                    "departureAt", java.util.Objects.toString(trip.getDepartureAt(), ""));
            releaseSeats(trip.getId(), b.getSeats());
            auditService.log(null, "BOOKING_EXPIRED", "booking", b.getId(),
                    Map.of("tripId", trip.getId().toString(), "seatsReleased", b.getSeats(),
                            "ttlMinutes", pendingPaymentTtlMinutes));
            notificationService.notify(passenger, NotificationType.BOOKING_EXPIRED, payload);
            log.info("Reservation {} expiree (paiement non recu sous {} min), places liberees",
                    b.getId(), pendingPaymentTtlMinutes);
        }
        return stale.size();
    }

    /**
     * Restitue des places au trajet et le repasse en PUBLISHED s il etait FULL (jamais s il
     * est CANCELLED/COMPLETED). Le trajet est charge sous verrou pessimiste et modifie en
     * memoire plutot que par une requete UPDATE en masse : celle-ci viderait le contexte de
     * persistance (clearAutomatically) et detacherait la reservation, dont les associations
     * paresseuses (passager, conducteur) sont encore lues ensuite par les appelants.
     * Le verrou serialise avec decrementSeatsIfAvailable (regle metier n.7).
     */
    private void releaseSeats(UUID tripId, int seats) {
        Trip trip = tripRepository.findByIdForUpdate(tripId).orElseThrow();
        trip.setSeatsAvailable(Math.min(trip.getSeatsTotal(), trip.getSeatsAvailable() + seats));
        if (trip.getStatus() == TripStatus.FULL) {
            trip.setStatus(TripStatus.PUBLISHED);
        }
        tripRepository.save(trip);
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
