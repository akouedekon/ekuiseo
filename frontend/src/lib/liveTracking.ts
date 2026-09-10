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
/** Cadence minimale d envoi imposee par le serveur (429 en deca), V28. */
export const POSITION_SERVER_MIN_INTERVAL_MS = 2_000
/** Cadence par defaut avant la premiere reponse du serveur (30 s avant le depart, contrat C). */
export const DEFAULT_INTERVAL_SECONDS = 30
/** Le conducteur est « proche » du point de rendez-vous en deca de cette distance (aligne sur DRIVER_NEARBY). */
export const NEARBY_KM = 1
/** Le conducteur est « arrive » en deca de cette distance (aligne sur DRIVER_ARRIVED). */
export const ARRIVED_KM = 0.15

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
 * Faut-il envoyer cette position ? Oui a la premiere, puis des que la cadence recommandee
 * par le serveur (`intervalMs`, 10 s par defaut) s est ecoulee ou que l appareil a parcouru
 * 50 m depuis le dernier envoi - sans jamais descendre sous les 2 s que le serveur impose.
 */
export function shouldSendPosition(
  previous: (LatLng & { sentAt: number }) | null,
  next: LatLng,
  now: number,
  intervalMs: number = POSITION_MIN_INTERVAL_MS,
): boolean {
  if (!previous) return true
  const elapsed = now - previous.sentAt
  if (elapsed < POSITION_SERVER_MIN_INTERVAL_MS) return false
  if (elapsed >= Math.max(POSITION_SERVER_MIN_INTERVAL_MS, intervalMs)) return true
  return haversineKm(previous.lat, previous.lng, next.lat, next.lng) * 1000 >= POSITION_MIN_DISTANCE_M
}

/* ------------------------------------------------------------- V28 : flux */

/** Cap ramene dans [0, 360[ ; null si absent ou non fini. */
export function normalizeHeading(heading: number | null | undefined): number | null {
  if (heading === null || heading === undefined || !Number.isFinite(heading)) return null
  const h = heading % 360
  return h < 0 ? h + 360 : h
}

/** Interpolation d un cap par le plus court arc (350° -> 10° passe par 0°, pas par 180°). */
export function interpolateHeading(from: number | null, to: number | null, t: number): number | null {
  if (to === null) return from
  if (from === null) return to
  let delta = ((to - from + 540) % 360) - 180
  if (delta < -180) delta += 360
  return normalizeHeading(from + delta * clamp01(t))
}

function clamp01(t: number): number {
  return t <= 0 ? 0 : t >= 1 ? 1 : t
}

export interface AnimatedPoint extends LatLng {
  heading: number | null
}

/**
 * Interpolation lineaire entre deux positions (fraction `t` de 0 a 1) : c est ce que la
 * carte dessine entre deux evenements, sur la duree de `intervalSeconds`, pour que le
 * marqueur glisse au lieu de sauter. Une fraction hors [0, 1] est bornee.
 */
export function interpolatePosition(from: AnimatedPoint, to: AnimatedPoint, t: number): AnimatedPoint {
  const k = clamp01(t)
  return {
    lat: from.lat + (to.lat - from.lat) * k,
    lng: from.lng + (to.lng - from.lng) * k,
    heading: interpolateHeading(from.heading, to.heading, k),
  }
}

/**
 * Duree de l animation entre deux positions : la cadence annoncee par le serveur, bornee
 * entre 1 s (reactif) et 15 s (une position toutes les 30 s ne doit pas mettre 30 s a
 * arriver a l ecran). Sous prefers-reduced-motion, l appelant n anime pas du tout.
 */
export function animationDurationMs(intervalSeconds: number | null | undefined): number {
  const seconds = intervalSeconds && Number.isFinite(intervalSeconds) ? intervalSeconds : DEFAULT_INTERVAL_SECONDS
  return Math.min(15_000, Math.max(1_000, seconds * 1_000))
}

/** Age d une position au-dela duquel elle est perimee (90 s) : plus jamais presentee comme actuelle. */
export function isPositionStale(ageSeconds: number | null | undefined): boolean {
  return ageSeconds !== null && ageSeconds !== undefined && ageSeconds > STALE_AFTER_SECONDS
}

/**
 * Decalage (ms) entre l horloge locale et celle du serveur, estime a la reception d une
 * reponse horodatee (`serverTime`) : local = serveur + offset. Il sert a dater les positions
 * du flux sans comparer l horloge du conducteur a celle du lecteur.
 */
export function clockOffsetMs(serverTimeIso: string | null | undefined, receivedAt: number): number {
  if (!serverTimeIso) return 0
  const server = new Date(serverTimeIso).getTime()
  return Number.isFinite(server) ? receivedAt - server : 0
}

/**
 * Age (s) d une position recue par le flux : l horodatage serveur de la mesure, ramene a
 * l horloge locale par le decalage estime, sans jamais etre negatif. En repli (decalage
 * inconnu et horodatage douteux), l age est compte depuis la reception locale.
 */
export function participantAgeSeconds(
  participant: { recordedAt: string; receivedAt: number; ageAtReceipt: number },
  now: number,
  offsetMs: number | null,
): number {
  if (offsetMs !== null) {
    const recorded = new Date(participant.recordedAt).getTime()
    if (Number.isFinite(recorded)) return Math.max(0, Math.floor((now - offsetMs - recorded) / 1000))
  }
  return participant.ageAtReceipt + Math.max(0, Math.floor((now - participant.receivedAt) / 1000))
}

/** « 2,4 km », « 850 m ». */
export function formatDistanceShort(km: number): string {
  if (km < 1) return `${Math.round(km * 1000)} m`
  const value = km < 10 ? Math.round(km * 10) / 10 : Math.round(km)
  return `${value.toLocaleString('fr-FR')} km`
}

/**
 * Phrase d approche pour le passager : « Conducteur à 2,4 km · ~7 min », « Conducteur à
 * moins de 1 km », « Conducteur arrivé ». Distance et duree sont des estimations.
 */
export function describeApproach(
  driver: LatLng & { speedKmh?: number | null },
  pickup: LatLng,
  label = 'Conducteur',
): { text: string; distanceKm: number; minutes: number; state: 'far' | 'nearby' | 'arrived' } {
  const distanceKm = remainingDistanceKm(driver, pickup)
  const minutes = estimateRemainingMinutes(distanceKm, driver.speedKmh)
  if (distanceKm <= ARRIVED_KM) return { text: `${label} arrivé`, distanceKm, minutes: 0, state: 'arrived' }
  if (distanceKm <= NEARBY_KM) {
    return { text: `${label} à ${formatDistanceShort(distanceKm)} · ~${minutes} min`, distanceKm, minutes, state: 'nearby' }
  }
  return { text: `${label} à ${formatDistanceShort(distanceKm)} · ~${minutes} min`, distanceKm, minutes, state: 'far' }
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
