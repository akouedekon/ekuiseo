package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.DriverPayout;
import bj.ekuiseo.api.domain.DriverPayoutItem;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;
import bj.ekuiseo.api.domain.enums.PayoutStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reversements conducteurs (regle metier n.12, corrigee par la regle n.21 -
 * paiement fractionne, migration V7) : la plateforme ne reverse au conducteur
 * que ce qu'elle a REELLEMENT encaisse en ligne via Kkiapay, jamais le prix
 * total de la reservation. Deux modes MoMo genent un reversement, avec un calcul
 * different (voir {@link #netAmount}) ; les reservations CASH n'en genent jamais
 * (la plateforme n'a rien encaisse pour elles).
 *
 * <p><b>Invariant nouveau, remplace l'ancien "net = amount - serviceFee"</b> : la
 * plateforme ne redistribue jamais plus qu'elle n'a reellement recu. En
 * MOMO_DEPOSIT, seul l'acompte a transite par Kkiapay - le solde est regle en
 * especes directement au conducteur par le passager, la plateforme n'a jamais vu
 * cet argent et ne peut donc pas le reverser une seconde fois.</p>
 *
 * <p><b>Limitation connue</b> : le decaissement reel (transfert d'argent vers le
 * numero mobile money du conducteur) n'est pas automatise ici. Aucune API de
 * transfert/payout Kkiapay n'a pu etre confirmee depuis cet environnement (voir
 * KkiapayGateway) : {@link #settle} se contente donc de marquer le lot comme
 * regle une fois le virement effectue manuellement par le back-office, et
 * consigne l'action dans le journal d'audit.</p>
 */
@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);
    private static final List<BookingStatus> PAYABLE_STATUSES = List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED);
    /** Les deux modes qui font transiter de l'argent par la plateforme (jamais CASH). */
    private static final List<PaymentMethod> PAYABLE_METHODS =
            List.of(PaymentMethod.MOMO_DEPOSIT, PaymentMethod.MOMO_FULL);

    private final BookingRepository bookingRepository;
    private final DriverPayoutRepository driverPayoutRepository;
    private final DriverPayoutItemRepository driverPayoutItemRepository;
    private final UserRepository userRepository;
    private final PaymentAccountRepository paymentAccountRepository;
    private final PayoutMapper payoutMapper;
    private final AuditService auditService;
    private final long minimumThresholdFcfa;

    public PayoutService(BookingRepository bookingRepository, DriverPayoutRepository driverPayoutRepository,
                          DriverPayoutItemRepository driverPayoutItemRepository, UserRepository userRepository,
                          PaymentAccountRepository paymentAccountRepository, PayoutMapper payoutMapper,
                          AuditService auditService,
                          @Value("${ekuiseo.payout.minimum-threshold-fcfa:2000}") long minimumThresholdFcfa) {
        this.bookingRepository = bookingRepository;
        this.driverPayoutRepository = driverPayoutRepository;
        this.driverPayoutItemRepository = driverPayoutItemRepository;
        this.userRepository = userRepository;
        this.paymentAccountRepository = paymentAccountRepository;
        this.payoutMapper = payoutMapper;
        this.auditService = auditService;
        this.minimumThresholdFcfa = minimumThresholdFcfa;
    }

    @Transactional(readOnly = true)
    public DriverBalanceResponse getBalance(UUID driverId) {
        long balance = bookingRepository.findPayableForDriver(driverId, PAYABLE_STATUSES, PAYABLE_METHODS).stream()
                .mapToLong(this::netAmount)
                .sum();
        return new DriverBalanceResponse(balance, minimumThresholdFcfa);
    }

    @Transactional(readOnly = true)
    public List<PayoutResponse> history(UUID driverId) {
        return driverPayoutRepository.findByDriverIdOrderByRequestedAtDesc(driverId).stream()
                .map(payoutMapper::toResponse).toList();
    }

    /**
     * Calcule et cree un lot de reversements (un DriverPayout PENDING par conducteur
     * eligible, regroupant toutes ses reservations MoMo payees non encore reversees).
     * N'inclut un conducteur que si son solde atteint le seuil minimum configure
     * (ekuiseo.payout.minimum-threshold-fcfa, 2000 FCFA par defaut). Declenche par
     * l'admin (voir AdminPayoutController) ; peut aussi etre appele par un scheduler
     * hebdomadaire externe si souhaite (non planifie automatiquement ici pour laisser
     * l'admin choisir le moment exact du lot).
     */
    @Transactional
    public PayoutBatchResultResponse runWeeklyBatch(UUID adminId) {
        List<UUID> driverIds = bookingRepository.findDriverIdsWithPayableBookings(PAYABLE_STATUSES, PAYABLE_METHODS);
        Instant now = Instant.now();
        List<PayoutResponse> created = new ArrayList<>();
        long totalAmount = 0;

        for (UUID driverId : driverIds) {
            List<Booking> payable = bookingRepository.findPayableForDriver(driverId, PAYABLE_STATUSES, PAYABLE_METHODS);
            long amount = payable.stream().mapToLong(this::netAmount).sum();
            if (amount < minimumThresholdFcfa) {
                continue; // reporte au prochain lot, sous le seuil minimum
            }
            User driver = userRepository.findById(driverId).orElseThrow(() -> new NotFoundException("Conducteur introuvable"));
            Instant periodStart = payable.stream().map(Booking::getCreatedAt).min(Instant::compareTo).orElse(now);

            DriverPayout payout = DriverPayout.builder()
                    .driver(driver)
                    .amount(amount)
                    .status(PayoutStatus.PENDING)
                    .destinationMsisdn(driver.getPhone())
                    .periodStart(periodStart)
                    .periodEnd(now)
                    .build();
            // Variable distincte (effectivement finale) pour la lambda ci-dessous :
            // "payout" est reaffectee, javac refuserait sa capture.
            DriverPayout savedPayout = driverPayoutRepository.save(payout);

            List<DriverPayoutItem> items = payable.stream()
                    .map(b -> DriverPayoutItem.builder().payout(savedPayout).bookingId(b.getId()).netAmount(netAmount(b)).build())
                    .toList();
            driverPayoutItemRepository.saveAll(items);

            created.add(payoutMapper.toResponse(savedPayout));
            totalAmount += amount;
        }

        auditService.log(adminId, "PAYOUT_BATCH_RUN", "driver_payout", null,
                Map.of("payoutsCreated", created.size(), "totalAmountFcfa", totalAmount));
        log.info("Lot de reversement : {} reversement(s) crees pour un total de {} FCFA", created.size(), totalAmount);
        return new PayoutBatchResultResponse(created.size(), totalAmount, created);
    }

    @Transactional(readOnly = true)
    public List<PayoutResponse> listAll() {
        return driverPayoutRepository.findAll().stream().map(payoutMapper::toResponse).toList();
    }

    /**
     * Vue back-office GET /api/v1/admin/payouts (voir AdminPayoutResponse pour
     * le detail des champs derives - driverName, provider, tripCount, statut au
     * vocabulaire front).
     */
    @Transactional(readOnly = true)
    public List<AdminPayoutResponse> listAllForAdmin() {
        return driverPayoutRepository.findAll().stream().map(this::toAdminResponse).toList();
    }

    private AdminPayoutResponse toAdminResponse(DriverPayout payout) {
        User driver = payout.getDriver();
        MobileMoneyOperator provider = paymentAccountRepository.findByUserIdAndIsDefaultTrue(driver.getId())
                .map(bj.ekuiseo.api.domain.PaymentAccount::getProvider)
                .orElse(MobileMoneyOperator.MTN_MOMO);
        long tripCount = driverPayoutItemRepository.countByPayoutId(payout.getId());
        return new AdminPayoutResponse(payout.getId(), driver.getId(),
                driver.getFirstName() + " " + driver.getLastName(), provider, payout.getDestinationMsisdn(),
                payout.getAmount(), tripCount, payout.getPeriodStart(), payout.getPeriodEnd(),
                toAdminStatus(payout.getStatus()), payout.getSettledAt());
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
        payout.setStatus(PayoutStatus.SETTLED);
        payout.setSettledAt(Instant.now());
        payout = driverPayoutRepository.save(payout);
        auditService.log(adminId, "PAYOUT_SETTLED", "driver_payout", payout.getId(),
                Map.of("amountFcfa", payout.getAmount(), "driverId", payout.getDriver().getId().toString()));
        return payoutMapper.toResponse(payout);
    }

    /**
     * Montant net reverse au conducteur pour une reservation (regle metier n.12,
     * corrigee par la regle n.21) : la plateforme ne redistribue que ce qu'elle a
     * reellement encaisse en ligne.
     * <ul>
     *   <li>{@code MOMO_FULL} : le passager a paye la totalite en ligne -&gt;
     *       net = amount - serviceFee (comportement historique inchange).</li>
     *   <li>{@code MOMO_DEPOSIT} : seul l'acompte a ete encaisse en ligne, le solde
     *       est regle en especes directement au conducteur -&gt;
     *       net = depositAmount - serviceFee (JAMAIS amount - serviceFee, qui
     *       crediterait a tort le conducteur d'un solde que la plateforme n'a
     *       jamais recu). Toujours &gt;= 0 : FeePolicy#computeDepositAmount garantit
     *       deposit &gt;= serviceFee a la creation de la reservation.</li>
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
