package bj.ekuiseo.api.service;

import java.util.UUID;

/**
 * Un trajet reservable vient d etre publie (creation directe ou occurrence d une navette).
 * Publie DANS la transaction de publication ; consomme apres son commit, en asynchrone,
 * par SearchAlertMatchService (constat F527) : un echec du matching ne fait jamais echouer
 * la publication, et la reponse HTTP n attend pas les notifications.
 */
public record TripPublishedEvent(UUID tripId) {
}
