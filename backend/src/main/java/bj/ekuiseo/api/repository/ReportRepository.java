package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.Report;
import bj.ekuiseo.api.domain.enums.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {
    Page<Report> findByStatus(ReportStatus status, Pageable pageable);

    /** Vue back-office (ReportService#listForAdmin) : parties chargees en une requete (constat F119). */
    @EntityGraph(attributePaths = {"reporter", "reportedUser", "reportedTrip", "reportedTrip.driver"})
    @Query("select r from Report r where r.status = :status")
    Page<Report> findByStatusWithParties(@Param("status") ReportStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"reporter", "reportedUser", "reportedTrip", "reportedTrip.driver"})
    @Query("select r from Report r")
    Page<Report> findAllWithParties(Pageable pageable);

    /** Dedoublonnage (constat F548) : un signalement encore ouvert du meme auteur vers le meme utilisateur. */
    boolean existsByReporterIdAndReportedUserIdAndStatusIn(UUID reporterId, UUID reportedUserId, List<ReportStatus> statuses);

    /** Dedoublonnage (constat F548) : un signalement encore ouvert du meme auteur vers le meme trajet. */
    boolean existsByReporterIdAndReportedTripIdAndStatusIn(UUID reporterId, UUID reportedTripId, List<ReportStatus> statuses);

    /** Plafond de signalements par auteur et par fenetre glissante (constat F548). */
    long countByReporterIdAndCreatedAtAfter(UUID reporterId, Instant after);

    /** File de moderation (GET /api/v1/admin/overview). */
    long countByStatus(ReportStatus status);

    /**
     * Autres signalements (tous statuts) visant la meme personne, directement ou via l un
     * de ses trajets (constat F307) : un recidiviste se repere avant de trancher.
     */
    @Query("select count(r) from Report r left join r.reportedTrip t where r.id <> :reportId "
            + "and (r.reportedUser.id = :targetId or t.driver.id = :targetId)")
    long countOthersAgainstTarget(@Param("reportId") UUID reportId, @Param("targetId") UUID targetId);

    /** Signalements deposes par un utilisateur (export de ses donnees, UserDataExportService). */
    List<Report> findByReporterIdOrderByCreatedAtDesc(UUID reporterId);

    /** Signalements lies a une reservation pour un motif donne (dossier « conducteur absent », V25). */
    List<Report> findByBookingIdAndReasonCodeAndStatusIn(UUID bookingId, String reasonCode, List<ReportStatus> statuses);
}
