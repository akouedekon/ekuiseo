package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.domain.PaymentAccount;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;
import bj.ekuiseo.api.dto.user.AddPaymentMethodRequest;
import bj.ekuiseo.api.dto.user.PaymentMethodResponse;
import bj.ekuiseo.api.repository.PaymentAccountRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constats F606/F607 : normalisation E.164, doublon 409, plafond de 3 comptes, coherence operateur / prefixe. */
class PaymentAccountServiceTest {

    private final PaymentAccountRepository repository = mock(PaymentAccountRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PaymentAccountService service = new PaymentAccountService(repository, userRepository,
            mock(AuditService.class), MobileMoneyPrefixes.defaults());

    private final User user = User.builder().id(UUID.randomUUID()).phone("+2290197000322").firstName("Awa").lastName("K").build();

    @BeforeEach
    void setUp() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(repository.findByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of());
        when(repository.save(any(PaymentAccount.class))).thenAnswer(inv -> {
            PaymentAccount a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
    }

    @Test
    void add_normalizesThePhone_andVerifiesTheLoginNumberAtOnce() {
        PaymentMethodResponse res = service.add(user.getId(),
                new AddPaymentMethodRequest(MobileMoneyOperator.MTN_MOMO, "01 97 00 03 22", " perso "));

        assertThat(res.phone()).isEqualTo("+2290197000322");
        assertThat(res.verified()).isTrue();
        assertThat(res.isDefault()).isTrue();
        assertThat(res.label()).isEqualTo("perso");
    }

    @Test
    void add_refusesADuplicateOfOperatorAndNumber() {
        when(repository.findByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(
                account(MobileMoneyOperator.MTN_MOMO, "+2290197000322")));

        assertThatThrownBy(() -> service.add(user.getId(),
                new AddPaymentMethodRequest(MobileMoneyOperator.MTN_MOMO, "0197000322", null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("deja enregistre");
        verify(repository, never()).save(any());
    }

    @Test
    void add_refusesBeyondThreeAccounts() {
        when(repository.findByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(
                account(MobileMoneyOperator.MTN_MOMO, "+2290197000322"),
                account(MobileMoneyOperator.MOOV_MONEY, "+2290155000001"),
                account(MobileMoneyOperator.CELTIIS_CASH, "+2290145000001")));

        assertThatThrownBy(() -> service.add(user.getId(),
                new AddPaymentMethodRequest(MobileMoneyOperator.MTN_MOMO, "0196000000", null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("3 comptes");
        verify(repository, never()).save(any());
    }

    @Test
    void add_refusesANumberOfAnotherOperator() {
        assertThatThrownBy(() -> service.add(user.getId(),
                new AddPaymentMethodRequest(MobileMoneyOperator.MOOV_MONEY, "0197000322", null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Moov Money");
        verify(repository, never()).save(any());
    }

    @Test
    void add_secondAccountIsNotDefault_andNotVerified() {
        when(repository.findByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(
                account(MobileMoneyOperator.MTN_MOMO, "+2290197000322")));

        PaymentMethodResponse res = service.add(user.getId(),
                new AddPaymentMethodRequest(MobileMoneyOperator.MOOV_MONEY, "0155000001", null));

        assertThat(res.isDefault()).isFalse();
        assertThat(res.verified()).isFalse();
    }

    private PaymentAccount account(MobileMoneyOperator provider, String phone) {
        return PaymentAccount.builder().id(UUID.randomUUID()).user(user).provider(provider).phone(phone).build();
    }
}
