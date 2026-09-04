package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.Report;
import bj.ekuiseo.api.domain.enums.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {
    Page<Report> findByStatus(ReportStatus status, Pageable pageable);
}
