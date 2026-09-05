package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.Report;
import bj.ekuiseo.api.domain.enums.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {
    Page<Report> findByStatus(ReportStatus status, Pageable pageable);

    /** Dedoublonnage (constat F548) : un signalement encore ouvert du meme auteur vers le meme utilisateur. */
    boolean existsByReporterIdAndReportedUserIdAndStatusIn(UUID reporterId, UUID reportedUserId, List<ReportStatus> statuses);

    /** Dedoublonnage (constat F548) : un signalement encore ouvert du meme auteur vers le meme trajet. */
    boolean existsByReporterIdAndReportedTripIdAndStatusIn(UUID reporterId, UUID reportedTripId, List<ReportStatus> statuses);

    /** Plafond de signalements par auteur et par fenetre glissante (constat F548). */
    long countByReporterIdAndCreatedAtAfter(UUID reporterId, Instant after);
}
