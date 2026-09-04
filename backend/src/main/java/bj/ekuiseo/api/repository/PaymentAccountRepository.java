package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.PaymentAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAccountRepository extends JpaRepository<PaymentAccount, UUID> {
    List<PaymentAccount> findByUserIdOrderByCreatedAtAsc(UUID userId);
    Optional<PaymentAccount> findByUserIdAndIsDefaultTrue(UUID userId);
    long countByUserId(UUID userId);
}
