package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.ReconciliationAnomaly;
import bj.ekuiseo.api.domain.enums.AnomalyKind;
import bj.ekuiseo.api.domain.enums.AnomalyStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReconciliationAnomalyRepository extends JpaRepository<ReconciliationAnomaly, UUID> {

    Page<ReconciliationAnomaly> findByStatusOrderByCreatedAtDesc(AnomalyStatus status, Pageable pageable);

    Page<ReconciliationAnomaly> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(AnomalyStatus status);

    /** Dedoublonnage : un ecart encore ouvert de meme nature sur le meme paiement n est pas recree. */
    boolean existsByKindAndPaymentIdAndStatus(AnomalyKind kind, UUID paymentId, AnomalyStatus status);

    boolean existsByKindAndProviderTxIdAndStatus(AnomalyKind kind, String providerTxId, AnomalyStatus status);
}
