import type { TripStopResponse } from '@/api/extended'
import { estimateDurationMinutes, haversineKm, isOutsideBenin } from '@/lib/cities'

export interface RoutePoint {
  label: string
  /** Heure prevue, ISO. Absente pour un arret non horodate. */
  time: string | null
  /** Heure calculee par le front (arrivee estimee), pas annoncee par le conducteur : affichee avec « ≈ ». */
  estimated?: boolean
  /** Prix depuis l'origine (0 a l'origine, prix plein a l'arrivee). */
  priceFromOrigin: number | null
  kind: 'origin' | 'stop' | 'destination'
}

/** Ce qu'il faut d'un trajet pour en estimer l'arrivee : coordonnees, libelles et depart. */
export interface RouteLike {
  originLat: number
  originLng: number
  originLabel?: string
  destLat: number
  destLng: number
  destLabel?: string
  departureAt: string
}

export interface ArrivalEstimate {
  /** Heure d'arrivee estimee, ISO. */
  arrivalIso: string
  /** Duree de route estimee, en minutes. */
  durationMinutes: number
  /** Distance orthodromique, en km. */
  distanceKm: number
}

/**
 * Arrivee et duree ESTIMEES d'un trajet (audits F239, F247) : modele a deux
 * vitesses de lib/cities.ts, + 45 min par frontiere. Aucune heure d'arrivee
 * n'est saisie par le conducteur : toujours afficher avec « ≈ » et un titre
 * « estimation ». Une seule implementation pour la carte de resultat, la fiche
 * et le tunnel de reservation.
 */
export function estimateArrival(trip: RouteLike): ArrivalEstimate {
  const distanceKm = haversineKm(trip.originLat, trip.originLng, trip.destLat, trip.destLng)
  const crossBorder =
    isOutsideBenin(trip.originLat, trip.originLng, trip.originLabel) !==
    isOutsideBenin(trip.destLat, trip.destLng, trip.destLabel)
  const durationMinutes = estimateDurationMinutes(distanceKm, { crossBorder })
  const arrivalIso = new Date(new Date(trip.departureAt).getTime() + durationMinutes * 60_000).toISOString()
  return { arrivalIso, durationMinutes, distanceKm }
}

export function buildRoutePoints(
  originLabel: string,
  destLabel: string,
  departureAt: string,
  arrivalAt: string,
  pricePerSeat: number,
  stops: TripStopResponse[],
): RoutePoint[] {
  return [
    { label: originLabel, time: departureAt, priceFromOrigin: 0, kind: 'origin' },
    ...stops
      .slice()
      .sort((a, b) => a.position - b.position)
      .map<RoutePoint>((stop) => ({
        label: stop.label,
        time: stop.plannedAt,
        priceFromOrigin: stop.priceFromOrigin,
        kind: 'stop',
      })),
    { label: destLabel, time: arrivalAt, estimated: true, priceFromOrigin: pricePerSeat, kind: 'destination' },
  ]
}
