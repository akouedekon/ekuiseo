package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Paging;
import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.DriverPayout;
import bj.ekuiseo.api.domain.LedgerEntry;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.Refund;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.AnomalyStatus;
import bj.ekuiseo.api.domain.enums.LedgerAccount;
import bj.ekuiseo.api.domain.enums.LedgerDirection;
import bj.ekuiseo.api.domain.enums.LedgerEntryType;
import bj.ekuiseo.api.domain.enums.PayoutStatus;
import bj.ekuiseo.api.dto.finance.FinanceSummaryResponse;
import bj.ekuiseo.api.dto.finance.LedgerAdjustmentRequest;
import bj.ekuiseo.api.dto.finance.LedgerEntryResponse;
import bj.ekuiseo.api.repository.DriverPayoutRepository;
import bj.ekuiseo.api.repository.LedgerEntryRepository;
import bj.ekuiseo.api.repository.ReconciliationAnomalyRepository;
import bj.ekuiseo.api.repository.RefundRepository;
import bj.ekuiseo.api.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Registre financier (contrat A.4, V26) : ecritures en ajout seul, posees DANS la transaction
 * de l evenement qu elles decrivent (paiement verifie, remboursement confirme, reversement
 * regle, especes reglees, correction admin), et lectures agregees en SQL.
 *
 * <p><b>Convention de sens</b> : DEBIT = l argent quitte le compte, CREDIT = il y entre.
 * <b>Equilibre d un paiement</b> : {@code PASSENGER_PAYMENT = PLATFORM_COMMISSION + DRIVER_SHARE}
 * (commission brute = min(frais de service de la reservation, montant verifie) ; part
 * conducteur = reste, jamais negatif). {@code PROVIDER_FEE} est un cout de la plateforme,
 * preleve sur sa commission (revenu net = commission - frais) : il ne fait pas partie de
 * l equilibre, sinon un paiement ne pourrait jamais s equilibrer avec des frais non nuls.
 * Un remboursement contre-passe la part conducteur et la commission au prorata du montant
 * rembourse, la part conducteur etant arrondie vers le bas et la commission absorbant le reste,
 * de sorte que {@code REFUND = DRIVER_SHARE_REVERSAL + COMMISSION_REVERSAL} exactement.</p>
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);
    public static final String PROVIDER_CASH = "CASH";
    static final int MAX_DAYS = 730;

    private final LedgerEntryRepository repository;
    private final DriverPayoutRepository driverPayoutRepository;
    private final ReconciliationAnomalyRepository anomalyRepository;
    private final RefundRepository refundRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public LedgerService(LedgerEntryRepository repository, DriverPayoutRepository driverPayoutRepository,
                         ReconciliationAnomalyRepository anomalyRepository, RefundRepository refundRepository,
                         UserRepository userRepository, AuditService auditService) {
        this.repository = repository;
        this.driverPayoutRepository = driverPayoutRepository;
        this.anomalyRepository = anomalyRepository;
        this.refundRepository = refundRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    /* ------------------------------------------------------------------ ecritures */

    /**
     * Paiement verifie SUCCEEDED : PASSENGER_PAYMENT (montant verifie), PROVIDER_FEE si le
     * fournisseur a renvoye des frais, PLATFORM_COMMISSION = min(frais de service, montant
     * verifie), DRIVER_SHARE = montant verifie - commission. Pour un abonnement conducteur
     * (pas de reservation), la totalite est commission de la plateforme. Idempotent : un paiement
     * deja porte au registre n y est pas reecrit (le webhook et le widget peuvent se croiser).
     */
    @Transactional
    public List<LedgerEntry> recordPaymentSucceeded(Payment payment, long verifiedAmountFcfa, long providerFeesFcfa) {
        if (payment.getId() == null) {
            throw new IllegalStateException("Le paiement doit etre persiste avant d etre porte au registre");
        }
        if (!repository.findByPaymentIdAndEntryType(payment.getId(), LedgerEntryType.PASSENGER_PAYMENT).isEmpty()) {
            log.info("Paiement {} deja au registre : ecritures non dupliquees", payment.getId());
            return List.of();
        }
        if (verifiedAmountFcfa <= 0) {
            return List.of();
        }
        Booking booking = payment.getBooking();
        UUID bookingId = booking != null ? booking.getId() : null;
        UUID payerId = booking != null ? booking.getPassenger().getId()
                : payment.getSubscription() != null ? payment.getSubscription().getDriver().getId() : null;
        UUID driverId = booking != null ? booking.getTrip().getDriver().getId() : null;
        long commission = booking != null ? Math.min(booking.getServiceFee(), verifiedAmountFcfa) : verifiedAmountFcfa;
        long driverShare = Math.max(0, verifiedAmountFcfa - commission);
        String provider = payment.getProvider().name();
        String reference = payment.getProviderTxId();
        String subject = booking != null ? "Acompte de la reservation" : "Abonnement conducteur";

        List<LedgerEntry> written = new ArrayList<>();
        written.add(save(LedgerEntry.builder()
                .entryType(LedgerEntryType.PASSENGER_PAYMENT).account(LedgerAccount.PASSENGER).direction(LedgerDirection.DEBIT)
                .amountFcfa(verifiedAmountFcfa).bookingId(bookingId).paymentId(payment.getId()).userId(payerId)
                .provider(provider).providerReference(reference).description(subject + " : montant verifie").build()));
        if (providerFeesFcfa > 0) {
            written.add(save(LedgerEntry.builder()
                    .entryType(LedgerEntryType.PROVIDER_FEE).account(LedgerAccount.PROVIDER).direction(LedgerDirection.CREDIT)
                    .amountFcfa(providerFeesFcfa).bookingId(bookingId).paymentId(payment.getId())
                    .provider(provider).providerReference(reference).description("Frais de l agregateur").build()));
        }
        if (commission > 0) {
            written.add(save(LedgerEntry.builder()
                    .entryType(LedgerEntryType.PLATFORM_COMMISSION).account(LedgerAccount.PLATFORM).direction(LedgerDirection.CREDIT)
                    .amountFcfa(commission).bookingId(bookingId).paymentId(payment.getId())
                    .provider(provider).providerReference(reference)
                    .description(booking != null ? "Commission de la plateforme" : "Abonnement conducteur").build()));
        }
        if (driverShare > 0) {
            written.add(save(LedgerEntry.builder()
                    .entryType(LedgerEntryType.DRIVER_SHARE).account(LedgerAccount.DRIVER).direction(LedgerDirection.CREDIT)
                    .amountFcfa(driverShare).bookingId(bookingId).paymentId(payment.getId()).userId(driverId)
                    .provider(provider).providerReference(reference).description("Part du conducteur, a reverser").build()));
        }
        return written;
    }

    /**
     * Remboursement confirme : REFUND (montant), puis DRIVER_SHARE_REVERSAL et COMMISSION_REVERSAL
     * au prorata du montant rembourse par rapport au montant encaisse. Idempotent par remboursement.
     */
    @Transactional
    public List<LedgerEntry> recordRefundSucceeded(Refund refund) {
        if (repository.existsByRefundIdAndEntryType(refund.getId(), LedgerEntryType.REFUND)) {
            return List.of();
        }
        Payment payment = refund.getPayment();
        Booking booking = payment.getBooking();
        UUID bookingId = refund.getBookingId() != null ? refund.getBookingId() : booking != null ? booking.getId() : null;
        UUID payerId = booking != null ? booking.getPassenger().getId()
                : payment.getSubscription() != null ? payment.getSubscription().getDriver().getId() : null;
        UUID driverId = booking != null ? booking.getTrip().getDriver().getId() : null;
        Split original = originalSplit(payment);
        long amount = refund.getAmountFcfa();
        if (amount <= 0) {
            return List.of();
        }
        long driverReversal = original.paid() <= 0 ? 0
                : Math.min(original.driverShare(), Math.floorDiv(original.driverShare() * amount, original.paid()));
        long commissionReversal = Math.max(0, amount - driverReversal);
        String provider = payment.getProvider().name();
        String reference = refund.getProviderReference() != null ? refund.getProviderReference() : payment.getProviderTxId();

        List<LedgerEntry> written = new ArrayList<>();
        written.add(save(LedgerEntry.builder()
                .entryType(LedgerEntryType.REFUND).account(LedgerAccount.PASSENGER).direction(LedgerDirection.CREDIT)
                .amountFcfa(amount).bookingId(bookingId).paymentId(payment.getId()).refundId(refund.getId()).userId(payerId)
                .provider(provider).providerReference(reference).description("Remboursement : " + refund.getReason()).build()));
        if (driverReversal > 0) {
            written.add(save(LedgerEntry.builder()
                    .entryType(LedgerEntryType.DRIVER_SHARE_REVERSAL).account(LedgerAccount.DRIVER).direction(LedgerDirection.DEBIT)
                    .amountFcfa(driverReversal).bookingId(bookingId).paymentId(payment.getId()).refundId(refund.getId()).userId(driverId)
                    .provider(provider).providerReference(reference).description("Contre-passation de la part du conducteur").build()));
        }
        if (commissionReversal > 0) {
            written.add(save(LedgerEntry.builder()
                    .entryType(LedgerEntryType.COMMISSION_REVERSAL).account(LedgerAccount.PLATFORM).direction(LedgerDirection.DEBIT)
                    .amountFcfa(commissionReversal).bookingId(bookingId).paymentId(payment.getId()).refundId(refund.getId())
                    .provider(provider).providerReference(reference).description("Contre-passation de la commission").build()));
        }
        return written;
    }

    /** Reversement regle par le back-office : PAYOUT (montant effectivement vire), compte DRIVER. Idempotent par lot. */
    @Transactional
    public void recordPayoutSettled(DriverPayout payout, long settledAmountFcfa) {
        if (settledAmountFcfa <= 0 || repository.existsByPayoutIdAndEntryType(payout.getId(), LedgerEntryType.PAYOUT)) {
            return;
        }
        save(LedgerEntry.builder()
                .entryType(LedgerEntryType.PAYOUT).account(LedgerAccount.DRIVER).direction(LedgerDirection.DEBIT)
                .amountFcfa(settledAmountFcfa).payoutId(payout.getId()).userId(payout.getDriver().getId())
                .provider(payout.getDestinationProvider() == null ? null : payout.getDestinationProvider().name())
                .providerReference(payout.getExternalReference()).description("Reversement au conducteur").build());
    }

    /** Solde en especes regle a bord (contrat A.6) : CASH_ON_BOARD, informatif, compte DRIVER, fournisseur CASH. */
    @Transactional
    public void recordCashSettled(Booking booking, long cashFcfa) {
        if (cashFcfa <= 0 || repository.existsByBookingIdAndEntryType(booking.getId(), LedgerEntryType.CASH_ON_BOARD)) {
            return;
        }
        save(LedgerEntry.builder()
                .entryType(LedgerEntryType.CASH_ON_BOARD).account(LedgerAccount.DRIVER).direction(LedgerDirection.CREDIT)
                .amountFcfa(cashFcfa).bookingId(booking.getId()).userId(booking.getTrip().getDriver().getId())
                .provider(PROVIDER_CASH).description("Solde regle en especes a bord").build());
    }

    /** Correction d administration (ADJUSTMENT) : description obligatoire, journalisee. */
    @Transactional
    public LedgerEntryResponse adjust(UUID adminId, LedgerAdjustmentRequest req) {
        if (req.description() == null || req.description().isBlank()) {
            throw new BadRequestException("La description d une correction est obligatoire");
        }
        if (req.amountFcfa() <= 0) {
            throw new BadRequestException("Le montant doit etre strictement positif");
        }
        LedgerEntry entry = save(LedgerEntry.builder()
                .entryType(LedgerEntryType.ADJUSTMENT).account(req.account()).direction(req.direction())
                .amountFcfa(req.amountFcfa()).bookingId(req.bookingId()).userId(req.userId())
                .description(req.description().trim()).createdBy(adminId).build());
        Map<String, Object> details = new HashMap<>();
        details.put("account", req.account().name());
        details.put("direction", req.direction().name());
        details.put("amountFcfa", req.amountFcfa());
        details.put("bookingId", Objects.toString(req.bookingId(), ""));
        details.put("userId", Objects.toString(req.userId(), ""));
        details.put("description", req.description().trim());
        auditService.log(adminId, "LEDGER_ADJUSTMENT", "ledger_entry", entry.getId(), details);
        return LedgerEntryResponse.from(entry, null);
    }

    private LedgerEntry save(LedgerEntry entry) {
        return repository.save(entry);
    }

    /** Montant encaisse, commission et part conducteur d origine, lus au registre ou reconstitues. */
    record Split(long paid, long commission, long driverShare) {
    }

    Split originalSplit(Payment payment) {
        List<LedgerEntry> paid = repository.findByPaymentIdAndEntryType(payment.getId(), LedgerEntryType.PASSENGER_PAYMENT);
        if (!paid.isEmpty()) {
            long total = paid.stream().mapToLong(LedgerEntry::getAmountFcfa).sum();
            long commission = repository.findByPaymentIdAndEntryType(payment.getId(), LedgerEntryType.PLATFORM_COMMISSION)
                    .stream().mapToLong(LedgerEntry::getAmountFcfa).sum();
            long driverShare = repository.findByPaymentIdAndEntryType(payment.getId(), LedgerEntryType.DRIVER_SHARE)
                    .stream().mapToLong(LedgerEntry::getAmountFcfa).sum();
            return new Split(total, commission, driverShare);
        }
        // Paiement anterieur au registre (V26) : reconstitue depuis la reservation.
        long total = payment.getVerifiedAmount() != null && payment.getVerifiedAmount() > 0
                ? payment.getVerifiedAmount() : payment.getAmount();
        Booking booking = payment.getBooking();
        long commission = booking != null ? Math.min(booking.getServiceFee(), total) : total;
        return new Split(total, commission, Math.max(0, total - commission));
    }

    /* ------------------------------------------------------------------ lectures */

    @Transactional(readOnly = true)
    public FinanceSummaryResponse summary(int days) {
        if (days <= 0 || days > MAX_DAYS) {
            throw new BadRequestException("days doit etre compris entre 1 et " + MAX_DAYS);
        }
        Instant to = Instant.now();
        Instant from = to.minus(days, ChronoUnit.DAYS);
        LedgerEntryRepository.LedgerTotals t = repository.getTotals(from, to);
        long pendingPayout = driverPayoutRepository.sumAmountByStatus(PayoutStatus.PENDING)
                + driverPayoutRepository.sumAmountByStatus(PayoutStatus.PROCESSING);
        List<FinanceSummaryResponse.Month> months = repository.getMonthlyTotals(from, to).stream()
                .map(m -> new FinanceSummaryResponse.Month(m.getMonth(), m.getPassengerPaid(), m.getCommission(),
                        m.getRefunded(), m.getPaidOut(), m.getCashOnBoard()))
                .toList();
        return new FinanceSummaryResponse(days, t.getPassengerPaid(), t.getProviderFees(),
                t.getPlatformCommission() - t.getCommissionReversed(), t.getDriverShare() - t.getDriverShareReversed(),
                t.getRefunded(), t.getPaidOut(), pendingPayout, t.getCashOnBoard(),
                anomalyRepository.countByStatus(AnomalyStatus.OPEN), refundRepository.countOpen(), months);
    }

    /** Filtres de recherche ; tout champ null est ignore, {@code to} est exclusif. */
    public record Filter(UUID bookingId, UUID userId, LedgerEntryType entryType, Instant from, Instant to) {
    }

    @Transactional(readOnly = true)
    public Page<LedgerEntryResponse> search(Filter filter, int page, int size) {
        Page<LedgerEntry> entries = repository.findAll(toSpecification(filter),
                Paging.of(page, size, Sort.by("createdAt").descending()));
        Map<UUID, String> names = namesOf(entries.getContent());
        return entries.map(e -> LedgerEntryResponse.from(e, e.getUserId() == null ? null : names.get(e.getUserId())));
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryResponse> forBooking(UUID bookingId) {
        List<LedgerEntry> entries = repository.findByBookingIdOrderByCreatedAtAsc(bookingId);
        Map<UUID, String> names = namesOf(entries);
        return entries.stream().map(e -> LedgerEntryResponse.from(e, e.getUserId() == null ? null : names.get(e.getUserId()))).toList();
    }

    private Map<UUID, String> namesOf(List<LedgerEntry> entries) {
        List<UUID> ids = entries.stream().map(LedgerEntry::getUserId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (User u : userRepository.findAllById(ids)) {
                names.put(u.getId(), (u.getFirstName() + " " + u.getLastName()).trim());
            }
        }
        return names;
    }

    static Specification<LedgerEntry> toSpecification(Filter f) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (f != null) {
                if (f.bookingId() != null) predicates.add(cb.equal(root.get("bookingId"), f.bookingId()));
                if (f.userId() != null) predicates.add(cb.equal(root.get("userId"), f.userId()));
                if (f.entryType() != null) predicates.add(cb.equal(root.get("entryType"), f.entryType()));
                if (f.from() != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.from()));
                if (f.to() != null) predicates.add(cb.lessThan(root.get("createdAt"), f.to()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Export CSV des ecritures d une periode (contrat A.4) : separateur ';', UTF-8 avec BOM,
     * montants entiers (pas de decimale), une ligne par ecriture, plus anciennes d abord.
     */
    @Transactional(readOnly = true)
    public String exportCsv(Instant from, Instant to) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(30, ChronoUnit.DAYS);
        if (!start.isBefore(end)) {
            throw new BadRequestException("La date de debut doit preceder la date de fin");
        }
        if (start.plus(MAX_DAYS, ChronoUnit.DAYS).isBefore(end)) {
            throw new BadRequestException("La periode d export ne peut pas depasser " + MAX_DAYS + " jours");
        }
        List<LedgerEntry> entries = repository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(start, end);
        Map<UUID, String> names = namesOf(entries);
        StringBuilder sb = new StringBuilder("﻿");
        row(sb, "id", "date", "type", "compte", "sens", "montant_fcfa", "reservation", "paiement", "remboursement",
                "reversement", "utilisateur", "nom", "fournisseur", "reference", "description");
        for (LedgerEntry e : entries) {
            row(sb, e.getId(), e.getCreatedAt(), e.getEntryType(), e.getAccount(), e.getDirection(), e.getAmountFcfa(),
                    e.getBookingId(), e.getPaymentId(), e.getRefundId(), e.getPayoutId(), e.getUserId(),
                    e.getUserId() == null ? "" : names.getOrDefault(e.getUserId(), ""), e.getProvider(),
                    e.getProviderReference(), e.getDescription());
        }
        return sb.toString();
    }

    private static void row(StringBuilder sb, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(csvCell(cells[i]));
        }
        sb.append('\n');
    }

    static String csvCell(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        if (s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
