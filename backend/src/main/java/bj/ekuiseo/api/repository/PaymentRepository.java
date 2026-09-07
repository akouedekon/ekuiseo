package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.enums.PaymentProvider;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByProviderAndProviderTxId(PaymentProvider provider, String providerTxId);
    List<Payment> findByBookingId(UUID bookingId);
    Optional<Payment> findFirstByBookingIdAndStatusOrderByCreatedAtDesc(UUID bookingId, PaymentStatus status);
    List<Payment> findBySubscriptionId(UUID subscriptionId);

    /** Paiement INITIATED d un abonnement, reutilise par le webhook et une nouvelle ouverture du widget (F149). */
    Optional<Payment> findFirstBySubscriptionIdAndStatusOrderByCreatedAtDesc(UUID subscriptionId, PaymentStatus status);

    /**
     * Paiement charge sous verrou pessimiste (SELECT ... FOR UPDATE, constat F150) : la
     * confirmation par le widget et le webhook Kkiapay peuvent arriver a la meme seconde
     * pour la meme transaction ; le second attend le commit du premier et retrouve alors
     * un paiement deja terminal.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Menage des paiements abandonnes (constat F019) : un paiement INITIATED de plus de
     * {@code before} qui porte encore la reference interne (le widget n a jamais remonte
     * d identifiant Kkiapay) et dont la reservation ou l abonnement n est plus en attente
     * passe FAILED, marque {@code decision = ABANDONED} dans raw_payload pour ne pas
     * compter comme un echec d operateur dans les KPI. Un paiement qui porte deja un
     * identifiant Kkiapay n est jamais touche : le webhook ou sa reprise tranchera.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update payments p
            set status = 'FAILED',
                updated_at = now(),
                raw_payload = coalesce(p.raw_payload, '{}'::jsonb) || '{"source":"housekeeping","decision":"ABANDONED"}'::jsonb
            where p.status = 'INITIATED'
              and p.created_at < :before
              and p.provider_tx_id like 'ekuiseo-%'
              and (p.booking_id is null
                   or not exists (select 1 from bookings b where b.id = p.booking_id and b.status = 'PENDING_PAYMENT'))
              and (p.subscription_id is null
                   or not exists (select 1 from driver_subscriptions s where s.id = p.subscription_id and s.status = 'PENDING_PAYMENT'))
            """, nativeQuery = true)
    int failAbandonedInitiated(@Param("before") Instant before);

    /** Remboursements a (re)tenter : demandes depuis plus de {@code before} et toujours en attente. */
    List<Payment> findByStatusAndRefundRequestedAtBefore(PaymentStatus status, Instant before);

    /** Vue back-office des paiements a suivre (remboursements en attente ou manuels), plus recents d abord. */
    @Query("select p from Payment p left join fetch p.booking b left join fetch b.passenger "
            + "where p.status in :statuses order by coalesce(p.refundRequestedAt, p.createdAt) desc")
    List<Payment> findForAdmin(@Param("statuses") List<PaymentStatus> statuses);

    /** Remboursements a traiter (GET /api/v1/admin/overview). */
    long countByStatusIn(List<PaymentStatus> statuses);

    /**
     * Paiements d un utilisateur : ceux de ses reservations (passager) et de ses abonnements
     * (conducteur), plus recents d abord. Fiche admin (GET /api/v1/admin/users/{id}/payments)
     * et export des donnees personnelles (UserDataExportService).
     */
    @Query(value = "select p from Payment p left join p.booking b left join p.subscription s "
            + "where b.passenger.id = :userId or s.driver.id = :userId order by p.createdAt desc",
            countQuery = "select count(p) from Payment p left join p.booking b left join p.subscription s "
                    + "where b.passenger.id = :userId or s.driver.id = :userId")
    Page<Payment> findByUserId(@Param("userId") UUID userId, Pageable pageable);

    /** Variante non paginee de {@link #findByUserId(UUID, Pageable)}, pour l export. */
    @Query("select p from Payment p left join p.booking b left join p.subscription s "
            + "where b.passenger.id = :userId or s.driver.id = :userId order by p.createdAt asc")
    List<Payment> findAllByUserId(@Param("userId") UUID userId);

    // ------------------------------------------------------------------
    // KPI de paiement (AdminRetentionService, point n.14) : agregation SQL,
    // jamais de chargement de paiements en memoire. Alias en snake_case.
    // ------------------------------------------------------------------

    /** Tentatives et echecs par operateur, pour {@link #getFailuresByOperator}. */
    interface OperatorFailureStats {
        String getOperator();

        long getAttempts();

        long getFailures();
    }

    /**
     * Taux d echec Kkiapay par operateur reel (payments.channel, alimente par la
     * verification, constat F140) sur les paiements crees sur [from, to) : une tentative
     * est un paiement tranche (SUCCEEDED, FAILED ou rembourse), un echec un FAILED. Les
     * paiements abandonnes par le menage ({@code decision = ABANDONED}) ne comptent ni
     * comme tentative ni comme echec : le passager n a jamais valide de paiement.
     */
    @Query(value = """
            select coalesce(p.channel, 'UNKNOWN') as operator,
                   count(*) filter (where p.status <> 'INITIATED'
                                      and coalesce(p.raw_payload ->> 'decision', '') <> 'ABANDONED') as attempts,
                   count(*) filter (where p.status = 'FAILED'
                                      and coalesce(p.raw_payload ->> 'decision', '') <> 'ABANDONED') as failures
            from payments p
            where p.provider = 'KKIAPAY'
              and p.created_at >= :from and p.created_at < :to
            group by 1
            order by 1
            """, nativeQuery = true)
    List<OperatorFailureStats> getFailuresByOperator(@Param("from") Instant from, @Param("to") Instant to);
}
