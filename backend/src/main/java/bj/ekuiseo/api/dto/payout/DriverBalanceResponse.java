package bj.ekuiseo.api.dto.payout;

/** Solde conducteur non encore reverse (regle metier n.12) : somme nette des reservations MoMo payees mais pas encore incluses dans un lot de reversement. */
public record DriverBalanceResponse(
        long pendingBalanceFcfa,
        long minimumPayoutThresholdFcfa
) {
}
