package bj.ekuiseo.api.dto.booking;

import bj.ekuiseo.api.domain.enums.CashStatus;

import java.time.Instant;

/** Reglement du solde en especes a bord (contrat A.6, V27) ; null dans les reponses quand aucun solde n est concerne. */
public record CashSettlementResponse(
        CashStatus status,
        long expectedFcfa,
        Instant driverConfirmedAt,
        Instant passengerConfirmedAt,
        Instant disputedAt
) {
}
