package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.PhoneNumbers;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.PaymentAccount;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.dto.payout.AdminPaymentAccountResponse;
import bj.ekuiseo.api.dto.user.AddPaymentMethodRequest;
import bj.ekuiseo.api.dto.user.PaymentMethodResponse;
import bj.ekuiseo.api.repository.PaymentAccountRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Comptes mobile money enregistres par l'utilisateur (regle metier n.18,
 * GET/POST/DELETE /api/v1/me/payment-methods). Au plus un moyen de paiement par
 * defaut (voir uq_payment_methods_default, migration V6) : le premier ajoute
 * devient automatiquement le defaut, et supprimer le defaut en promeut un autre
 * si un moyen de paiement reste.
 *
 * <p>Lot 1.2 (F602/F603/F605) : le numero est normalise en E.164 et un compte n est
 * <b>destination de reversement</b> qu une fois verifie ({@code verifiedAt}) : d office
 * quand le numero est celui du compte (identifiant controle), sinon par un
 * administrateur apres verification hors ligne (journalisee). En attendant, le compte
 * sert seulement a pre-remplir le paiement des acomptes.</p>
 */
@Service
public class PaymentAccountService {

    private final PaymentAccountRepository paymentAccountRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public PaymentAccountService(PaymentAccountRepository paymentAccountRepository, UserRepository userRepository,
                                 AuditService auditService) {
        this.paymentAccountRepository = paymentAccountRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<PaymentMethodResponse> list(UUID userId) {
        return paymentAccountRepository.findByUserIdOrderByCreatedAtAsc(userId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public PaymentMethodResponse add(UUID userId, AddPaymentMethodRequest req) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
        String phone = PhoneNumbers.normalize(req.phone());
        boolean duplicate = paymentAccountRepository.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .anyMatch(a -> a.getPhone().equals(phone) && a.getProvider() == req.provider());
        if (duplicate) {
            throw new ConflictException("Ce compte est deja enregistre");
        }
        boolean makeDefault = paymentAccountRepository.countByUserId(userId) == 0;
        PaymentAccount account = PaymentAccount.builder()
                .user(user)
                .provider(req.provider())
                .phone(phone)
                .label(req.label() == null ? null : req.label().trim())
                .isDefault(makeDefault)
                // Le numero de connexion est le seul identifiant controle : un compte qui le porte
                // est verifie d office. Tout autre numero attend une verification admin.
                .verifiedAt(phone.equals(user.getPhone()) ? Instant.now() : null)
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

    /* ------------------------------------------------------------------ back-office */

    @Transactional(readOnly = true)
    public List<AdminPaymentAccountResponse> listForAdmin(Boolean verified) {
        List<PaymentAccount> all = verified == null
                ? paymentAccountRepository.findAllByOrderByCreatedAtDesc()
                : verified ? paymentAccountRepository.findByVerifiedAtIsNotNullOrderByCreatedAtDesc()
                : paymentAccountRepository.findByVerifiedAtIsNullOrderByCreatedAtDesc();
        return all.stream().map(this::toAdmin).toList();
    }

    @Transactional
    public AdminPaymentAccountResponse verifyByAdmin(UUID adminId, UUID accountId) {
        PaymentAccount account = paymentAccountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Compte mobile money introuvable"));
        if (account.getVerifiedAt() == null) {
            account.setVerifiedAt(Instant.now());
            account = paymentAccountRepository.save(account);
            auditService.log(adminId, "PAYMENT_ACCOUNT_VERIFIED", "payment_account", accountId,
                    Map.of("userId", account.getUser().getId().toString(), "phone", account.getPhone(),
                            "provider", account.getProvider().name()));
        }
        return toAdmin(account);
    }

    private PaymentMethodResponse toResponse(PaymentAccount a) {
        return new PaymentMethodResponse(a.getId(), a.getProvider(), a.getPhone(), a.getLabel(), a.isDefault(),
                a.getVerifiedAt() != null);
    }

    private AdminPaymentAccountResponse toAdmin(PaymentAccount a) {
        User u = a.getUser();
        return new AdminPaymentAccountResponse(a.getId(), u.getId(), u.getFirstName() + " " + u.getLastName(),
                u.getPhone(), a.getProvider(), a.getPhone(), a.getLabel(), a.isDefault(), a.getVerifiedAt(),
                a.getCreatedAt());
    }
}
