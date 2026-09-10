import { haversineKm } from '@/lib/cities'

/*
 * Suivi en direct d un trajet (V23) : fonctions pures partagees par le conducteur
 * (LiveSharingControl), les passagers (LiveTrackingCard) et le lien public
 * (LiveTrackingPage). Tout ce qui est calcule ici est une ESTIMATION affichee comme
 * telle : la seule verite est la position recue du serveur et son horodatage.
 */

/** Au-dela de cet age, une position est consideree perimee (le vehicule a peut-etre perdu le reseau). */
export const STALE_AFTER_SECONDS = 90
/** Vitesse moyenne retenue quand le navigateur ne rapporte pas de vitesse fiable. */
export const DEFAULT_SPEED_KMH = 40
/** En dessous, la vitesse observee ne dit rien de la suite (feu, embouteillage, arret) : on retombe sur la moyenne. */
export const MIN_RELIABLE_SPEED_KMH = 8
/** Le conducteur peut activer le partage a partir d une heure avant le depart (aligne sur TripLiveService). */
export const SHARING_WINDOW_BEFORE_MS = 60 * 60 * 1000
/** Envoi d une position : au plus toutes les 10 s, ou des 50 m parcourus. */
export const POSITION_MIN_INTERVAL_MS = 10_000
export const POSITION_MIN_DISTANCE_M = 50
/** Rafraichissement des lecteurs (passagers, lien public). */
export const LIVE_REFRESH_INTERVAL_MS = 10_000

export interface LatLng {
  lat: number
  lng: number
}

/** Distance restante a vol d oiseau jusqu a la destination, en km (jamais negative). */
export function remainingDistanceKm(position: LatLng, destination: LatLng): number {
  return Math.max(0, haversineKm(position.lat, position.lng, destination.lat, destination.lng))
}

/**
 * Vitesse retenue pour l estimation : la vitesse observee quand elle est connue et
 * plausible, sinon 40 km/h de moyenne (routes beninoises, arrets compris).
 */
export function effectiveSpeedKmh(speedKmh: number | null | undefined): number {
  if (speedKmh === null || speedKmh === undefined || !Number.isFinite(speedKmh)) return DEFAULT_SPEED_KMH
  if (speedKmh < MIN_RELIABLE_SPEED_KMH) return DEFAULT_SPEED_KMH
  return Math.min(speedKmh, 130)
}

/** Minutes restantes ESTIMEES (au moins 1 tant qu il reste de la distance). */
export function estimateRemainingMinutes(distanceKm: number, speedKmh: number | null | undefined): number {
  if (distanceKm <= 0) return 0
  return Math.max(1, Math.round((distanceKm / effectiveSpeedKmh(speedKmh)) * 60))
}

/** Heure d arrivee ESTIMEE (ISO) a partir de la derniere position et de son horodatage. */
export function estimateArrivalIso(
  position: LatLng & { speedKmh?: number | null; recordedAt: string },
  destination: LatLng,
): string {
  const minutes = estimateRemainingMinutes(remainingDistanceKm(position, destination), position.speedKmh)
  return new Date(new Date(position.recordedAt).getTime() + minutes * 60_000).toISOString()
}

/** Age d une position en secondes, jamais negatif (horloge de l appareil en avance). */
export function ageSeconds(recordedAt: string | Date, now: number = Date.now()): number {
  return Math.max(0, Math.floor((now - new Date(recordedAt).getTime()) / 1000))
}

export function isStale(recordedAt: string | Date, now: number = Date.now()): boolean {
  return ageSeconds(recordedAt, now) > STALE_AFTER_SECONDS
}

/** « à l instant », « il y a 12 s », « il y a 3 min », « il y a 2 h ». */
export function formatAge(seconds: number): string {
  if (seconds < 5) return 'à l’instant'
  if (seconds < 60) return `il y a ${seconds} s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `il y a ${minutes} min`
  const hours = Math.floor(minutes / 60)
  return `il y a ${hours} h`
}

/**
 * Fenetre pendant laquelle le conducteur peut partager sa position : d une heure avant
 * le depart jusqu a la fin du trajet (statut COMPLETED / CANCELLED exclu). Meme regle
 * que TripLiveService cote serveur, qui reste l arbitre.
 */
export function isSharingWindowOpen(
  trip: { departureAt: string; status: string },
  now: number = Date.now(),
): boolean {
  if (trip.status !== 'PUBLISHED' && trip.status !== 'FULL' && trip.status !== 'ONGOING') return false
  return now >= new Date(trip.departureAt).getTime() - SHARING_WINDOW_BEFORE_MS
}

/**
 * Faut-il envoyer cette position ? Oui a la premiere, puis des que 10 s se sont ecoulees
 * ou que le vehicule a parcouru 50 m depuis le dernier envoi.
 */
export function shouldSendPosition(
  previous: (LatLng & { sentAt: number }) | null,
  next: LatLng,
  now: number,
): boolean {
  if (!previous) return true
  if (now - previous.sentAt >= POSITION_MIN_INTERVAL_MS) return true
  return haversineKm(previous.lat, previous.lng, next.lat, next.lng) * 1000 >= POSITION_MIN_DISTANCE_M
}

/**
 * Age d une position tel que le serveur l a mesure (\`staleSeconds\`), avance du temps
 * ecoule depuis la reponse : on ne compare jamais l horodatage de l appareil du
 * conducteur a l horloge de celui du lecteur (les deux derivent).
 */
export function liveAgeSeconds(staleSeconds: number | null | undefined, dataUpdatedAt: number, now: number): number | null {
  if (staleSeconds === null || staleSeconds === undefined) return null
  return staleSeconds + Math.max(0, Math.floor((now - dataUpdatedAt) / 1000))
}

/** Vehicule a dessiner sur la carte (RouteMap) a partir d une position et de son age. */
export function toVehicle(
  position: (LatLng & { heading: number | null }) | null | undefined,
  age: number | null,
): { lat: number; lng: number; heading: number | null; stale: boolean } | null {
  if (!position) return null
  return { lat: position.lat, lng: position.lng, heading: position.heading, stale: age !== null && age > STALE_AFTER_SECONDS }
}

/**
 * Projection d un point sur une polyligne (en coordonnees deja projetees, ex. le cadre
 * SVG du trace stylise) : renvoie le point de la polyligne le plus proche. Sert au repli
 * schematique de RouteMap, ou une position reelle n aurait aucun sens hors du trace.
 */
export function projectOntoPolyline(point: { x: number; y: number }, polyline: { x: number; y: number }[]): { x: number; y: number } {
  if (polyline.length === 0) return point
  if (polyline.length === 1) return { x: polyline[0].x, y: polyline[0].y }
  let best = { x: polyline[0].x, y: polyline[0].y }
  let bestDistance = Number.POSITIVE_INFINITY
  for (let i = 0; i < polyline.length - 1; i++) {
    const a = polyline[i]
    const b = polyline[i + 1]
    const dx = b.x - a.x
    const dy = b.y - a.y
    const lengthSquared = dx * dx + dy * dy
    const t = lengthSquared === 0 ? 0 : Math.max(0, Math.min(1, ((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared))
    const candidate = { x: a.x + t * dx, y: a.y + t * dy }
    const distance = (candidate.x - point.x) ** 2 + (candidate.y - point.y) ** 2
    if (distance < bestDistance) {
      bestDistance = distance
      best = candidate
    }
  }
  return best
}

/**
 * Position geographique projetee sur le trace du trajet (origine, arrets, destination),
 * en degres : la fraction parcourue est mesuree le long de la polyligne geographique.
 * Utile pour dessiner le vehicule « sur la route » quand il n y a pas de fond de carte.
 */
export function projectOntoRoute(position: LatLng, route: LatLng[]): LatLng {
  const projected = projectOntoPolyline(
    { x: position.lng, y: position.lat },
    route.map((p) => ({ x: p.lng, y: p.lat })),
  )
  return { lat: projected.y, lng: projected.x }
}
