package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.DriverPayout;
import bj.ekuiseo.api.domain.DriverPayoutItem;
import bj.ekuiseo.api.domain.PaymentAccount;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.PayoutStatus;
import bj.ekuiseo.api.dto.payout.AdminPayoutResponse;
import bj.ekuiseo.api.dto.payout.DriverBalanceResponse;
import bj.ekuiseo.api.dto.payout.PayoutBatchResultResponse;
import bj.ekuiseo.api.dto.payout.PayoutResponse;
import bj.ekuiseo.api.mapper.PayoutMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverPayoutItemRepository;
import bj.ekuiseo.api.repository.DriverPayoutRepository;
import bj.ekuiseo.api.repository.PaymentAccountRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reversements conducteurs (regle metier n.12, corrigee par la regle n.21 -
 * paiement fractionne, migration V7) : la plateforme ne reverse au conducteur
 * que ce qu'elle a REELLEMENT encaisse en ligne via Kkiapay, jamais le prix
 * total de la reservation. Deux modes MoMo generent un reversement, avec un calcul
 * different (voir {@link #netAmount}) ; les reservations CASH n'en generent jamais.
 *
 * <p><b>Lot 1.2 de l audit</b> (F006/F102/F103/F602) :</p>
 * <ul>
 *   <li>une reservation n est reversable qu une fois le depart passe depuis
 *       {@code ekuiseo.payout.eligibility-delay-hours} (24 h) ET si son acompte a
 *       reellement ete encaisse (paiement SUCCEEDED, non rembourse) ;</li>
 *   <li>la destination est le compte mobile money <b>verifie</b> par defaut du
 *       conducteur, fige sur le lot (numero + operateur) ; sans compte, le conducteur
 *       est exclu du lot, liste dans le resultat et notifie ;</li>
 *   <li>une reservation remboursee apres inclusion dans un lot en est retiree (lot
 *       PENDING) ou marquee a deduire (lot deja traite), jamais oubliee.</li>
 * </ul>
 *
 * <p><b>Limitation connue</b> : le decaissement reel (transfert d'argent vers le
 * numero mobile money du conducteur) n'est pas automatise ici. Aucune API de
 * transfert/payout Kkiapay n'a pu etre confirmee ; {@link #settle} marque le lot
 * comme regle une fois le virement effectue manuellement par le back-office.</p>
 */
@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);
    private static final List<BookingStatus> PAYABLE_STATUSES = List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED);
    /** Les deux modes qui font transiter de l'argent par la plateforme (jamais CASH). */
    private static final List<PaymentMethod> PAYABLE_METHODS =
            List.of(PaymentMethod.MOMO_DEPOSIT, PaymentMethod.MOMO_FULL);
    public static final String SKIP_NO_ACCOUNT = "Aucun compte mobile money verifie";

    private final BookingRepository bookingRepository;
    private final DriverPayoutRepository driverPayoutRepository;
    private final DriverPayoutItemRepository driverPayoutItemRepository;
    private final UserRepository userRepository;
    private final PaymentAccountRepository paymentAccountRepository;
    private final PayoutMapper payoutMapper;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final long minimumThresholdFcfa;
    private final long eligibilityDelayHours;

    public PayoutService(BookingRepository bookingRepository, DriverPayoutRepository driverPayoutRepository,
                          DriverPayoutItemRepository driverPayoutItemRepository, UserRepository userRepository,
                          PaymentAccountRepository paymentAccountRepository, PayoutMapper payoutMapper,
                          AuditService auditService, NotificationService notificationService,
                          @Value("${ekuiseo.payout.minimum-threshold-fcfa:2000}") long minimumThresholdFcfa,
                          @Value("${ekuiseo.payout.eligibility-delay-hours:24}") long eligibilityDelayHours) {
        this.bookingRepository = bookingRepository;
        this.driverPayoutRepository = driverPayoutRepository;
        this.driverPayoutItemRepository = driverPayoutItemRepository;
        this.userRepository = userRepository;
        this.paymentAccountRepository = paymentAccountRepository;
        this.payoutMapper = payoutMapper;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.minimumThresholdFcfa = minimumThresholdFcfa;
        this.eligibilityDelayHours = eligibilityDelayHours;
    }

    private Instant eligibilityCutoff() {
        return Instant.now().minus(eligibilityDelayHours, ChronoUnit.HOURS);
    }

    @Transactional(readOnly = true)
    public DriverBalanceResponse getBalance(UUID driverId) {
        long balance = bookingRepository.findPayableForDriver(driverId, PAYABLE_STATUSES, PAYABLE_METHODS, eligibilityCutoff())
                .stream().mapToLong(this::netAmount).sum();
        return new DriverBalanceResponse(balance, minimumThresholdFcfa);
    }

    @Transactional(readOnly = true)
    public List<PayoutResponse> history(UUID driverId) {
        return driverPayoutRepository.findByDriverIdOrderByRequestedAtDesc(driverId).stream()
                .map(payoutMapper::toResponse).toList();
    }

    /**
     * Calcule et cree un lot de reversements (un DriverPayout PENDING par conducteur
     * eligible, regroupant toutes ses reservations MoMo payees, voyagees et non encore
     * reversees). N'inclut un conducteur que si son solde atteint le seuil minimum et
     * qu il a un compte mobile money verifie par defaut ; sinon il est liste dans
     * {@code skipped} et notifie. Declenche par l'admin (AdminPayoutController).
     */
    @Transactional
    public PayoutBatchResultResponse runWeeklyBatch(UUID adminId) {
        Instant cutoff = eligibilityCutoff();
        List<UUID> driverIds = bookingRepository.findDriverIdsWithPayableBookings(PAYABLE_STATUSES, PAYABLE_METHODS, cutoff);
        Instant now = Instant.now();
        List<PayoutResponse> created = new ArrayList<>();
        List<PayoutBatchResultResponse.SkippedDriver> skipped = new ArrayList<>();
        long totalAmount = 0;

        for (UUID driverId : driverIds) {
            List<Booking> payable = bookingRepository.findPayableForDriver(driverId, PAYABLE_STATUSES, PAYABLE_METHODS, cutoff);
            long amount = payable.stream().mapToLong(this::netAmount).sum();
            if (amount < minimumThresholdFcfa) {
                continue; // reporte au prochain lot, sous le seuil minimum
            }
            User driver = userRepository.findById(driverId).orElseThrow(() -> new NotFoundException("Conducteur introuvable"));
            Optional<PaymentAccount> account = paymentAccountRepository.findByUserIdAndIsDefaultTrue(driverId)
                    .filter(a -> a.getVerifiedAt() != null);
            if (account.isEmpty()) {
                skipped.add(new PayoutBatchResultResponse.SkippedDriver(driverId,
                        driver.getFirstName() + " " + driver.getLastName(), amount, SKIP_NO_ACCOUNT));
                notificationService.notify(driver, NotificationType.PAYOUT_ACCOUNT_MISSING,
                        Map.of("amountFcfa", amount));
                continue;
            }
            Instant periodStart = payable.stream().map(Booking::getCreatedAt).min(Instant::compareTo).orElse(now);

            DriverPayout payout = DriverPayout.builder()
                    .driver(driver)
                    .amount(amount)
                    .status(PayoutStatus.PENDING)
                    .destinationMsisdn(account.get().getPhone())
                    .destinationProvider(account.get().getProvider())
                    .periodStart(periodStart)
                    .periodEnd(now)
                    .build();
            DriverPayout savedPayout = driverPayoutRepository.save(payout);

            List<DriverPayoutItem> items = payable.stream()
                    .map(b -> DriverPayoutItem.builder().payout(savedPayout).bookingId(b.getId()).netAmount(netAmount(b)).build())
                    .toList();
            driverPayoutItemRepository.saveAll(items);

            created.add(payoutMapper.toResponse(savedPayout));
            totalAmount += amount;
        }

        auditService.log(adminId, "PAYOUT_BATCH_RUN", "driver_payout", null,
                Map.of("payoutsCreated", created.size(), "totalAmountFcfa", totalAmount, "skipped", skipped.size()));
        log.info("Lot de reversement : {} reversement(s) crees pour {} FCFA, {} conducteur(s) sans compte verifie",
                created.size(), totalAmount, skipped.size());
        return new PayoutBatchResultResponse(created.size(), totalAmount, created, skipped);
    }

    /**
     * Reservation annulee/remboursee : si elle figure dans un lot PENDING, elle en est
     * retiree et le lot recalcule (supprime s il se vide) ; si le lot est deja traite,
     * l item est marque a deduire et l audit le signale (constats F006/F102).
     */
    @Transactional
    public void excludeCancelledBooking(Booking booking, String reason) {
        Optional<DriverPayoutItem> found = driverPayoutItemRepository.findByBookingId(booking.getId());
        if (found.isEmpty()) return;
        DriverPayoutItem item = found.get();
        DriverPayout payout = item.getPayout();
        if (payout.getStatus() == PayoutStatus.PENDING) {
            driverPayoutItemRepository.delete(item);
            long remaining = payout.getAmount() - item.getNetAmount();
            long left = driverPayoutItemRepository.countByPayoutId(payout.getId()) - 1;
            if (left <= 0) {
                driverPayoutRepository.delete(payout);
                auditService.log(null, "PAYOUT_EMPTIED", "driver_payout", payout.getId(),
                        Map.of("bookingId", booking.getId().toString(), "reason", reason));
            } else {
                payout.setAmount(Math.max(0, remaining));
                driverPayoutRepository.save(payout);
                auditService.log(null, "PAYOUT_ITEM_REMOVED", "driver_payout", payout.getId(),
                        Map.of("bookingId", booking.getId().toString(), "netAmountFcfa", item.getNetAmount(),
                                "newAmountFcfa", payout.getAmount(), "reason", reason));
            }
            return;
        }
        if (item.getReversedAt() == null) {
            item.setReversedAt(Instant.now());
            item.setReversalReason(reason);
            driverPayoutItemRepository.save(item);
            auditService.log(null, "PAYOUT_ALREADY_INCLUDED", "driver_payout", payout.getId(),
                    Map.of("bookingId", booking.getId().toString(), "netAmountFcfa", item.getNetAmount(),
                            "payoutStatus", payout.getStatus().name(), "reason", reason));
            log.warn("Reservation {} remboursee alors que le lot {} est {} : {} FCFA a deduire du prochain virement",
                    booking.getId(), payout.getId(), payout.getStatus(), item.getNetAmount());
        }
    }

    @Transactional(readOnly = true)
    public List<PayoutResponse> listAll() {
        return driverPayoutRepository.findAll().stream().map(payoutMapper::toResponse).toList();
    }

    /** Vue back-office GET /api/v1/admin/payouts (voir AdminPayoutResponse). */
    @Transactional(readOnly = true)
    public List<AdminPayoutResponse> listAllForAdmin() {
        return driverPayoutRepository.findAll().stream().map(this::toAdminResponse).toList();
    }

    private AdminPayoutResponse toAdminResponse(DriverPayout payout) {
        User driver = payout.getDriver();
        long tripCount = driverPayoutItemRepository.countByPayoutId(payout.getId());
        long reversedCount = driverPayoutItemRepository.countByPayoutIdAndReversedAtIsNotNull(payout.getId());
        long reversedAmount = reversedCount == 0 ? 0 : driverPayoutItemRepository.sumReversedByPayoutId(payout.getId());
        return new AdminPayoutResponse(payout.getId(), driver.getId(),
                driver.getFirstName() + " " + driver.getLastName(), payout.getDestinationProvider(),
                payout.getDestinationMsisdn(), payout.getAmount(), tripCount, payout.getPeriodStart(),
                payout.getPeriodEnd(), toAdminStatus(payout.getStatus()), payout.getSettledAt(),
                reversedCount, reversedAmount);
    }

    /** PENDING/PROCESSING/FAILED sont identiques ; SETTLED (interne) devient PAID (vocabulaire front, extended.ts). */
    private String toAdminStatus(PayoutStatus status) {
        return status == PayoutStatus.SETTLED ? "PAID" : status.name();
    }

    /** Marque un reversement comme regle (virement effectue manuellement, voir limitation en tete de classe). */
    @Transactional
    public PayoutResponse settle(UUID adminId, UUID payoutId) {
        DriverPayout payout = driverPayoutRepository.findById(payoutId)
                .orElseThrow(() -> new NotFoundException("Reversement introuvable"));
        if (payout.getStatus() == PayoutStatus.SETTLED) {
            throw new ConflictException("Ce reversement est deja regle");
        }
        if (payout.getDestinationMsisdn() == null) {
            throw new ConflictException("Ce lot n a pas de compte mobile money de destination");
        }
        long reversedCount = driverPayoutItemRepository.countByPayoutIdAndReversedAtIsNotNull(payoutId);
        payout.setStatus(PayoutStatus.SETTLED);
        payout.setSettledAt(Instant.now());
        payout = driverPayoutRepository.save(payout);
        auditService.log(adminId, "PAYOUT_SETTLED", "driver_payout", payout.getId(),
                Map.of("amountFcfa", payout.getAmount(), "driverId", payout.getDriver().getId().toString(),
                        "destination", String.valueOf(payout.getDestinationMsisdn()), "reversedItems", reversedCount));
        return payoutMapper.toResponse(payout);
    }

    /**
     * Montant net reverse au conducteur pour une reservation (regle metier n.12,
     * corrigee par la regle n.21) : la plateforme ne redistribue que ce qu'elle a
     * reellement encaisse en ligne.
     * <ul>
     *   <li>{@code MOMO_FULL} : net = amount - serviceFee.</li>
     *   <li>{@code MOMO_DEPOSIT} : net = depositAmount - serviceFee (jamais amount -
     *       serviceFee, qui crediterait un solde que la plateforme n'a jamais recu).</li>
     * </ul>
     * CASH n'atteint jamais cette methode (exclu en amont par {@link #PAYABLE_METHODS}).
     */
    private long netAmount(Booking booking) {
        long collected = booking.getPaymentMethod() == PaymentMethod.MOMO_FULL
                ? booking.getAmount()
                : booking.getDepositAmount();
        return collected - booking.getServiceFee();
    }
}
