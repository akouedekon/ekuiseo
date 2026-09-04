package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.PaymentAccount;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.dto.user.AddPaymentMethodRequest;
import bj.ekuiseo.api.dto.user.PaymentMethodResponse;
import bj.ekuiseo.api.repository.PaymentAccountRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Comptes mobile money enregistres par l'utilisateur (regle metier n.18,
 * GET/POST/DELETE /api/v1/me/payment-methods). Au plus un moyen de paiement par
 * defaut (voir uq_payment_methods_default, migration V6) : le premier ajoute
 * devient automatiquement le defaut, et supprimer le defaut en promeut un autre
 * si un moyen de paiement reste.
 */
@Service
public class PaymentAccountService {

    private final PaymentAccountRepository paymentAccountRepository;
    private final UserRepository userRepository;

    public PaymentAccountService(PaymentAccountRepository paymentAccountRepository, UserRepository userRepository) {
        this.paymentAccountRepository = paymentAccountRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<PaymentMethodResponse> list(UUID userId) {
        return paymentAccountRepository.findByUserIdOrderByCreatedAtAsc(userId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public PaymentMethodResponse add(UUID userId, AddPaymentMethodRequest req) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
        boolean makeDefault = paymentAccountRepository.countByUserId(userId) == 0;
        PaymentAccount account = PaymentAccount.builder()
                .user(user)
                .provider(req.provider())
                .phone(req.phone())
                .label(req.label())
                .isDefault(makeDefault)
                .build();
        return toResponse(paymentAccountRepository.save(account));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        PaymentAccount account = paymentAccountRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Moyen de paiement introuvable"));
        if (!account.getUser().getId().equals(userId)) {
            throw new ForbiddenException("Ce moyen de paiement ne vous appartient pas");
        }
        boolean wasDefault = account.isDefault();
        paymentAccountRepository.delete(account);
        if (wasDefault) {
            // Promeut le moyen de paiement le plus ancien restant, s'il y en a un
            // (jamais deux par defaut a la fois, voir uq_payment_methods_default).
            paymentAccountRepository.findByUserIdOrderByCreatedAtAsc(userId).stream().findFirst()
                    .ifPresent(next -> {
                        next.setDefault(true);
                        paymentAccountRepository.save(next);
                    });
        }
    }

    private PaymentMethodResponse toResponse(PaymentAccount a) {
        return new PaymentMethodResponse(a.getId(), a.getProvider(), a.getPhone(), a.getLabel(), a.isDefault());
    }
}
