package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.SearchAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface SearchAlertRepository extends JpaRepository<SearchAlert, UUID> {

    List<SearchAlert> findByUserIdOrderByCreatedAtDesc(UUID userId);
    /** Anonymisation d un compte (UserService#anonymize). */
    void deleteByUserId(UUID userId);

    /**
     * Alertes actives dont la fenetre de dates (si renseignee) couvre la date de
     * depart donnee. Le filtre geospatial fin (rayon autour de l'origine/destination
     * de l'alerte) est applique ensuite en memoire via TripRepository#matchesAlertGeography,
     * pour eviter de dupliquer la logique ST_DWithin dans deux entites JPA distinctes.
     */
    @Query("select a from SearchAlert a where a.active = true "
            + "and (a.dateFrom is null or a.dateFrom <= :departureDate) "
            + "and (a.dateTo is null or a.dateTo >= :departureDate)")
    List<SearchAlert> findActiveCandidates(@Param("departureDate") LocalDate departureDate);

    /** Retention (RetentionScheduler, constat F525) : une alerte datee s eteint une fois sa fenetre passee. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SearchAlert a set a.active = false where a.active = true and a.dateTo is not null and a.dateTo < :today")
    int deactivateExpired(@Param("today") LocalDate today);

    /** Retention : alertes inactives creees avant la date. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SearchAlert a where a.active = false and a.createdAt < :before")
    int deleteInactiveCreatedBefore(@Param("before") Instant before);
}
