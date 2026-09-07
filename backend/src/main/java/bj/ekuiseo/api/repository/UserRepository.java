package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByPhone(String phone);
    boolean existsByPhone(String phone);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCaseAndIdNot(String email, UUID id);
    boolean existsByPhoneAndIdNot(String phone, UUID id);

    /** Purge des comptes jamais verifies (AuthHousekeepingScheduler) ; aucune donnee liee n existe encore. */
    @Modifying
    @Query("delete from User u where u.status = :status and u.createdAt < :before")
    int deleteByStatusAndCreatedAtBefore(@Param("status") UserStatus status, @Param("before") Instant before);
    Page<User> findByStatus(UserStatus status, Pageable pageable);
    long countByCreatedAtBetween(Instant from, Instant to);

    /** Annulation tardive comptee en base, sans lecture-modification-ecriture (constat F147). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.lateCancellationsCount = u.lateCancellationsCount + 1 where u.id = :id")
    int incrementLateCancellations(@Param("id") UUID id);

    /**
     * Note moyenne (2 decimales) et nombre d avis recalcules par la base a partir de la table
     * reviews (constat F147) : deux avis simultanes ne s ecrasent plus mutuellement.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update users u
            set rating_avg = coalesce((select round(avg(r.rating), 2) from reviews r where r.target_id = u.id), 0),
                rating_count = (select count(*) from reviews r where r.target_id = u.id)
            where u.id = :id
            """, nativeQuery = true)
    int recomputeRating(@Param("id") UUID id);

    /**
     * Recherche libre pour le back-office (GET /api/v1/admin/users?q=...) : nom,
     * prenom, telephone ou e-mail contenant le terme recherche (insensible a la
     * casse). {@code pageable} sert uniquement a plafonner le resultat (la liste
     * est renvoyee a plat cote controller, pas paginee, pour coller au contrat
     * front) : voir AdminUserService#search.
     */
    @Query("select u from User u where "
            + "lower(u.firstName) like lower(concat('%', :q, '%')) "
            + "or lower(u.lastName) like lower(concat('%', :q, '%')) "
            + "or u.phone like concat('%', :q, '%') "
            + "or lower(coalesce(u.email, '')) like lower(concat('%', :q, '%')) "
            + "order by u.createdAt desc")
    Page<User> search(@Param("q") String q, Pageable pageable);
}
