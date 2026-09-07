package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {
    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    Optional<Message> findFirstByConversationIdOrderByCreatedAtDesc(UUID conversationId);

    /** Messages envoyes par un utilisateur (export de ses donnees, UserDataExportService). */
    List<Message> findBySenderIdOrderByCreatedAtAsc(UUID senderId);

    /** Anonymisation d un compte (UserService#anonymize) : le contenu des messages envoyes est efface, la ligne reste. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Message m set m.body = :body where m.sender.id = :senderId")
    int redactBySender(@Param("senderId") UUID senderId, @Param("body") String body);

    /**
     * Retention (RetentionScheduler, constat F553) : messages des trajets partis avant la date,
     * sauf quand un signalement encore ouvert (OPEN / IN_REVIEW) vise le trajet, la
     * reservation, ou l un des deux participants - la moderation peut avoir besoin de
     * l echange. Les conversations videes sont supprimees ensuite par
     * {@link ConversationRepository#deleteEmptyForTripsDepartedBefore}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            delete from messages m
            using conversations c
            join bookings b on b.id = c.booking_id
            join trips t on t.id = b.trip_id
            where m.conversation_id = c.id
              and t.departure_at < :before
              and not exists (
                  select 1 from reports r
                  where r.status in ('OPEN', 'IN_REVIEW')
                    and (r.reported_trip_id = t.id
                         or r.booking_id = b.id
                         or r.reported_user_id in (b.passenger_id, t.driver_id))
              )
            """, nativeQuery = true)
    int deleteForTripsDepartedBefore(@Param("before") Instant before);

    /** Messages non lus dans une conversation, envoyes par l'AUTRE participant (jamais mes propres messages). */
    long countByConversationIdAndReadAtIsNullAndSenderIdNot(UUID conversationId, UUID senderId);

    /**
     * Comptes ayant envoye strictement plus de {@code threshold} messages depuis {@code since}
     * (indicateur d abus du tableau de bord admin, constat F555). Une seule agregation sur
     * l index messages(sender_id, created_at) de V15.
     */
    @Query(value = """
            select count(*) from (
                select m.sender_id
                from messages m
                where m.created_at >= :since
                group by m.sender_id
                having count(*) > :threshold
            ) heavy
            """, nativeQuery = true)
    long countHeavySenders(@Param("since") Instant since, @Param("threshold") int threshold);

    /** Variante par reservation (evite un aller-retour via ConversationRepository, voir BookingDetailResponse.unreadMessages). */
    long countByConversation_Booking_IdAndReadAtIsNullAndSenderIdNot(UUID bookingId, UUID senderId);

    /** Resultat brut (minutes, taille d'echantillon), pour {@link #getResponseTimeStats}. */
    interface DriverResponseTimeStats {
        Double getMedianMinutes();

        long getSampleSize();
    }

    /**
     * Delai median de premiere reponse d'un conducteur a ses passagers (profil public,
     * GET /api/v1/users/{id}, regle metier n.22), sur les 90 derniers jours : une seule
     * requete d'agregation SQL (CTE + percentile_cont), jamais le chargement des messages
     * en memoire - ce profil est consulte frequemment.
     *
     * <p>Pour chaque conversation du conducteur (jointe via bookings/trips), on retient le
     * premier message envoye par le PASSAGER (l'ouverture d'echange) puis le premier
     * message envoye par le CONDUCTEUR strictement apres celui-ci (la premiere reponse) ;
     * l'ecart entre les deux, en minutes, alimente {@code percentile_cont(0.5)}
     * (mediane, robuste aux quelques conversations ou la reponse met des heures).
     * Une conversation sans premier message passager, ou sans reponse conducteur
     * ulterieure, ne contribue a aucun echantillon (ni numerateur ni denominateur) -
     * ce n'est pas un "delai infini" compte comme un echec, simplement une donnee
     * absente.</p>
     *
     * <p>S'appuie sur l'index composite {@code idx_messages_conversation_sender_created}
     * (migration V8) : {@code messages(conversation_id, sender_id, created_at)} sert a
     * la fois le filtre passager et le filtre conducteur de chaque CTE sans balayer les
     * messages d'un tiers.</p>
     */
    @Query(value = """
            with driver_conversations as (
                select c.id as conversation_id, b.passenger_id
                from conversations c
                join bookings b on b.id = c.booking_id
                join trips t on t.id = b.trip_id
                where t.driver_id = :driverId
            ),
            first_passenger_msg as (
                select dc.conversation_id, min(m.created_at) as passenger_first_at
                from driver_conversations dc
                join messages m on m.conversation_id = dc.conversation_id
                                and m.sender_id = dc.passenger_id
                                and m.created_at >= :since
                group by dc.conversation_id
            ),
            first_driver_reply as (
                select fpm.conversation_id, min(m.created_at) as driver_reply_at
                from first_passenger_msg fpm
                join messages m on m.conversation_id = fpm.conversation_id
                                and m.sender_id = :driverId
                                and m.created_at > fpm.passenger_first_at
                group by fpm.conversation_id
            )
            select
              percentile_cont(0.5) within group (
                order by extract(epoch from (fdr.driver_reply_at - fpm.passenger_first_at)) / 60.0
              ) as median_minutes,
              count(*) as sample_size
            from first_passenger_msg fpm
            join first_driver_reply fdr on fdr.conversation_id = fpm.conversation_id
            """, nativeQuery = true)
    // Alias en snake_case (jamais camelCase) : Postgres replie tout alias non
    // quote en minuscules, et le mapping releche de Spring Data vers une
    // projection par interface se base sur cette convention underscore -> camelCase
    // pour retrouver getMedianMinutes()/getSampleSize() sans ambiguite.
    DriverResponseTimeStats getResponseTimeStats(@Param("driverId") UUID driverId, @Param("since") Instant since);
}
