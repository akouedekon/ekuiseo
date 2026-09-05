package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
    Page<User> findByStatus(UserStatus status, Pageable pageable);
    long countByCreatedAtBetween(Instant from, Instant to);

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
