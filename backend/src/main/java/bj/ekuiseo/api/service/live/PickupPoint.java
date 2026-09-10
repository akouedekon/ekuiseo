package bj.ekuiseo.api.service.live;

import bj.ekuiseo.api.domain.Booking;

/**
 * Point de prise en charge d un passager confirme : l origine du trajet, ou l arret
 * {@code pickupStopId} de sa reservation quand il monte en route. La reservation porte
 * l etat des deux notifications d approche (V28).
 */
record PickupPoint(Booking booking, double lat, double lng) {
}
