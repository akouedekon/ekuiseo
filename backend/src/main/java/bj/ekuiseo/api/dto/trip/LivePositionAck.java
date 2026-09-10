package bj.ekuiseo.api.dto.trip;

import java.util.List;

/**
 * Reponse de POST /api/v1/trips/{id}/live/positions (contrat C, V28). {@code accepted} est
 * faux quand la position n a pas ete diffusee (hors zone, teleportation) ; {@code flags}
 * porte les anomalies relevees, diffusee ou non ; {@code intervalSeconds} est la cadence
 * d envoi recommandee a l appareil (30 s avant le depart, 15 s pendant, 5 s a l approche
 * d un point de prise en charge).
 */
public record LivePositionAck(boolean accepted, List<String> flags, int intervalSeconds) {
}
