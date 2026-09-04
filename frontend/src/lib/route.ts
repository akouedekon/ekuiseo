import type { TripStopResponse } from '@/api/extended'

export interface RoutePoint {
  label: string
  /** Heure prevue, ISO. Absente pour un arret non horodate. */
  time: string | null
  /** Prix depuis l'origine (0 a l'origine, prix plein a l'arrivee). */
  priceFromOrigin: number | null
  kind: 'origin' | 'stop' | 'destination'
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
    { label: destLabel, time: arrivalAt, priceFromOrigin: pricePerSeat, kind: 'destination' },
  ]
}
