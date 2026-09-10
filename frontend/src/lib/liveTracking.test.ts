import { describe, expect, it } from 'vitest'
import {
  ageSeconds,
  animationDurationMs,
  clockOffsetMs,
  DEFAULT_SPEED_KMH,
  describeApproach,
  effectiveSpeedKmh,
  estimateArrivalIso,
  estimateRemainingMinutes,
  formatAge,
  formatDistanceShort,
  interpolateHeading,
  interpolatePosition,
  isPositionStale,
  isSharingWindowOpen,
  isStale,
  liveAgeSeconds,
  normalizeHeading,
  participantAgeSeconds,
  projectOntoPolyline,
  projectOntoRoute,
  remainingDistanceKm,
  shouldSendPosition,
  toVehicle,
} from './liveTracking'

const COTONOU = { lat: 6.3703, lng: 2.3912 }
const BOHICON = { lat: 7.1782, lng: 2.0667 }

describe('distance restante et arrivee estimee', () => {
  it('mesure la distance a vol d oiseau jusqu a la destination', () => {
    const km = remainingDistanceKm(COTONOU, BOHICON)
    expect(km).toBeGreaterThan(90)
    expect(km).toBeLessThan(100)
    expect(remainingDistanceKm(BOHICON, BOHICON)).toBe(0)
  })

  it('retient la vitesse observee quand elle est plausible, 40 km/h sinon', () => {
    expect(effectiveSpeedKmh(62)).toBe(62)
    expect(effectiveSpeedKmh(null)).toBe(DEFAULT_SPEED_KMH)
    expect(effectiveSpeedKmh(undefined)).toBe(DEFAULT_SPEED_KMH)
    expect(effectiveSpeedKmh(0)).toBe(DEFAULT_SPEED_KMH)
    expect(effectiveSpeedKmh(3)).toBe(DEFAULT_SPEED_KMH)
    expect(effectiveSpeedKmh(Number.NaN)).toBe(DEFAULT_SPEED_KMH)
    expect(effectiveSpeedKmh(400)).toBe(130)
  })

  it('estime les minutes restantes, au moins 1 tant qu il reste de la route', () => {
    expect(estimateRemainingMinutes(40, null)).toBe(60)
    expect(estimateRemainingMinutes(60, 60)).toBe(60)
    expect(estimateRemainingMinutes(0.1, 80)).toBe(1)
    expect(estimateRemainingMinutes(0, 80)).toBe(0)
  })

  it('date l arrivee a partir de l horodatage de la position, pas de l heure locale', () => {
    // 20 km a 40 km/h = 30 min apres la mesure.
    const position = { lat: 6.3703, lng: 2.3912, speedKmh: null, recordedAt: '2026-09-10T08:00:00Z' }
    const destination = { lat: 6.3703 + 20 / 111.2, lng: 2.3912 }
    const arrival = new Date(estimateArrivalIso(position, destination)).getTime()
    expect(arrival - Date.parse('2026-09-10T08:00:00Z')).toBe(30 * 60_000)
  })
})

describe('fraicheur', () => {
  const now = Date.parse('2026-09-10T08:00:00Z')

  it('compte l age en secondes, jamais negatif', () => {
    expect(ageSeconds('2026-09-10T07:59:48Z', now)).toBe(12)
    expect(ageSeconds('2026-09-10T08:00:30Z', now)).toBe(0)
  })

  it('declare une position perimee au-dela de 90 s', () => {
    expect(isStale('2026-09-10T07:58:31Z', now)).toBe(false)
    expect(isStale('2026-09-10T07:58:29Z', now)).toBe(true)
  })

  it('formate l age en francais', () => {
    expect(formatAge(2)).toBe('à l’instant')
    expect(formatAge(12)).toBe('il y a 12 s')
    expect(formatAge(185)).toBe('il y a 3 min')
    expect(formatAge(2 * 3600 + 10)).toBe('il y a 2 h')
  })

  it('avance l age mesure par le serveur du temps ecoule depuis la reponse, sans lire l horloge du conducteur', () => {
    expect(liveAgeSeconds(12, now - 8_000, now)).toBe(20)
    expect(liveAgeSeconds(12, now + 5_000, now)).toBe(12)
    expect(liveAgeSeconds(null, now, now)).toBeNull()
    expect(liveAgeSeconds(undefined, now, now)).toBeNull()
  })

  it('construit le vehicule de la carte, attenue au-dela de 90 s', () => {
    const position = { lat: 6.4, lng: 2.35, heading: 310 }
    expect(toVehicle(position, 30)).toEqual({ lat: 6.4, lng: 2.35, heading: 310, stale: false })
    expect(toVehicle(position, 91)).toEqual({ lat: 6.4, lng: 2.35, heading: 310, stale: true })
    expect(toVehicle({ ...position, heading: null }, null)).toEqual({ lat: 6.4, lng: 2.35, heading: null, stale: false })
    expect(toVehicle(null, 10)).toBeNull()
  })
})

describe('fenetre de partage du conducteur', () => {
  const departureAt = '2026-09-10T08:00:00Z'

  it('s ouvre une heure avant le depart et reste ouverte pendant le trajet', () => {
    expect(isSharingWindowOpen({ departureAt, status: 'PUBLISHED' }, Date.parse('2026-09-10T06:59:59Z'))).toBe(false)
    expect(isSharingWindowOpen({ departureAt, status: 'PUBLISHED' }, Date.parse('2026-09-10T07:00:00Z'))).toBe(true)
    expect(isSharingWindowOpen({ departureAt, status: 'FULL' }, Date.parse('2026-09-10T07:30:00Z'))).toBe(true)
    expect(isSharingWindowOpen({ departureAt, status: 'ONGOING' }, Date.parse('2026-09-10T10:00:00Z'))).toBe(true)
  })

  it('est fermee sur un trajet termine, annule, brouillon ou modele', () => {
    const at = Date.parse('2026-09-10T09:00:00Z')
    for (const status of ['COMPLETED', 'CANCELLED', 'DRAFT', 'TEMPLATE']) {
      expect(isSharingWindowOpen({ departureAt, status }, at)).toBe(false)
    }
  })
})

describe('cadence d envoi des positions', () => {
  const start = { lat: 6.3703, lng: 2.3912 }

  it('envoie la premiere position, puis toutes les 10 s ou tous les 50 m', () => {
    expect(shouldSendPosition(null, start, 0)).toBe(true)
    const previous = { ...start, sentAt: 100_000 }
    // Meme endroit, 4 s plus tard : rien.
    expect(shouldSendPosition(previous, start, 104_000)).toBe(false)
    // Meme endroit, 10 s plus tard : oui.
    expect(shouldSendPosition(previous, start, 110_000)).toBe(true)
    // 30 m plus loin, 4 s plus tard : non ; 60 m plus loin : oui.
    expect(shouldSendPosition(previous, { lat: start.lat + 30 / 111_200, lng: start.lng }, 104_000)).toBe(false)
    expect(shouldSendPosition(previous, { lat: start.lat + 60 / 111_200, lng: start.lng }, 104_000)).toBe(true)
  })

  it('suit la cadence du serveur sans jamais descendre sous 2 s, meme en mouvement', () => {
    const previous = { ...start, sentAt: 100_000 }
    // Cadence 5 s : 4 s -> non, 5 s -> oui.
    expect(shouldSendPosition(previous, start, 104_000, 5_000)).toBe(false)
    expect(shouldSendPosition(previous, start, 105_000, 5_000)).toBe(true)
    // 200 m plus loin mais 1,5 s apres : le serveur refuserait (429), on n envoie pas.
    expect(shouldSendPosition(previous, { lat: start.lat + 200 / 111_200, lng: start.lng }, 101_500, 5_000)).toBe(false)
    expect(shouldSendPosition(previous, { lat: start.lat + 200 / 111_200, lng: start.lng }, 102_000, 5_000)).toBe(true)
    // Cadence 30 s : 20 s sans bouger -> non.
    expect(shouldSendPosition(previous, start, 120_000, 30_000)).toBe(false)
  })
})

describe('interpolation et cap (V28)', () => {
  it('normalise le cap et interpole par le plus court arc', () => {
    expect(normalizeHeading(370)).toBe(10)
    expect(normalizeHeading(-30)).toBe(330)
    expect(normalizeHeading(null)).toBeNull()
    expect(normalizeHeading(Number.NaN)).toBeNull()
    expect(interpolateHeading(350, 10, 0.5)).toBe(0)
    expect(interpolateHeading(10, 350, 0.5)).toBe(0)
    expect(interpolateHeading(90, 180, 0.5)).toBe(135)
    expect(interpolateHeading(null, 45, 0.3)).toBe(45)
    expect(interpolateHeading(45, null, 0.3)).toBe(45)
  })

  it('interpole lineairement entre deux positions, fraction bornee', () => {
    const from = { lat: 6.0, lng: 2.0, heading: 0 }
    const to = { lat: 7.0, lng: 3.0, heading: 90 }
    expect(interpolatePosition(from, to, 0.25)).toEqual({ lat: 6.25, lng: 2.25, heading: 22.5 })
    expect(interpolatePosition(from, to, -1)).toEqual(from)
    expect(interpolatePosition(from, to, 2)).toEqual(to)
  })

  it('anime sur la cadence du serveur, entre 1 et 15 s', () => {
    expect(animationDurationMs(5)).toBe(5_000)
    expect(animationDurationMs(15)).toBe(15_000)
    expect(animationDurationMs(30)).toBe(15_000)
    expect(animationDurationMs(0.2)).toBe(1_000)
    expect(animationDurationMs(null)).toBe(15_000)
  })
})

describe('fraicheur des participants (V28)', () => {
  const now = Date.parse('2026-09-10T08:00:00Z')

  it('declare une position perimee au-dela de 90 s, jamais sans age', () => {
    expect(isPositionStale(90)).toBe(false)
    expect(isPositionStale(91)).toBe(true)
    expect(isPositionStale(null)).toBe(false)
    expect(isPositionStale(undefined)).toBe(false)
  })

  it('estime le decalage d horloge et date les positions du flux avec lui', () => {
    // L appareil est 5 s en avance sur le serveur.
    const offset = clockOffsetMs('2026-09-10T07:59:55Z', now)
    expect(offset).toBe(5_000)
    expect(clockOffsetMs(null, now)).toBe(0)
    expect(clockOffsetMs('n importe quoi', now)).toBe(0)
    const participant = { recordedAt: '2026-09-10T07:59:43Z', receivedAt: now - 2_000, ageAtReceipt: 0 }
    // Mesure a 07:59:43 serveur = 07:59:48 local ; il est 08:00:00 local : 12 s.
    expect(participantAgeSeconds(participant, now, offset)).toBe(12)
    // Sans decalage connu : age serveur a la reception + temps ecoule localement.
    expect(participantAgeSeconds({ ...participant, ageAtReceipt: 30 }, now, null)).toBe(32)
    // Jamais negatif.
    expect(participantAgeSeconds({ ...participant, recordedAt: '2026-09-10T08:10:00Z' }, now, offset)).toBe(0)
  })
})

describe('approche du conducteur (V28)', () => {
  const pickup = { lat: 6.3703, lng: 2.3912 }

  it('formate les distances courtes en metres et les longues en km', () => {
    expect(formatDistanceShort(0.85)).toBe('850 m')
    expect(formatDistanceShort(2.44)).toBe('2,4 km')
    expect(formatDistanceShort(23.6)).toBe('24 km')
  })

  it('decrit la distance, la duree estimee et l etat d approche', () => {
    const far = describeApproach({ lat: pickup.lat + 2.4 / 111.2, lng: pickup.lng, speedKmh: 20 }, pickup)
    expect(far.state).toBe('far')
    expect(far.text).toBe('Conducteur à 2,4 km · ~7 min')
    const nearby = describeApproach({ lat: pickup.lat + 0.5 / 111.2, lng: pickup.lng, speedKmh: null }, pickup)
    expect(nearby.state).toBe('nearby')
    expect(nearby.text).toMatch(/^Conducteur à \d+ m · ~1 min$/)
    const arrived = describeApproach({ lat: pickup.lat + 0.05 / 111.2, lng: pickup.lng }, pickup, 'Rodrigue')
    expect(arrived).toMatchObject({ state: 'arrived', text: 'Rodrigue arrivé', minutes: 0 })
  })
})

describe('projection sur le trace', () => {
  it('projette un point sur le segment le plus proche, borne aux extremites', () => {
    const line = [
      { x: 0, y: 0 },
      { x: 10, y: 0 },
      { x: 10, y: 10 },
    ]
    expect(projectOntoPolyline({ x: 5, y: 3 }, line)).toEqual({ x: 5, y: 0 })
    expect(projectOntoPolyline({ x: 12, y: 8 }, line)).toEqual({ x: 10, y: 8 })
    expect(projectOntoPolyline({ x: -4, y: -4 }, line)).toEqual({ x: 0, y: 0 })
    expect(projectOntoPolyline({ x: 3, y: 3 }, [])).toEqual({ x: 3, y: 3 })
    expect(projectOntoPolyline({ x: 3, y: 3 }, [{ x: 1, y: 1 }])).toEqual({ x: 1, y: 1 })
  })

  it('projette une position geographique sur l itineraire', () => {
    const route = [COTONOU, BOHICON]
    const midway = { lat: (COTONOU.lat + BOHICON.lat) / 2 + 0.05, lng: (COTONOU.lng + BOHICON.lng) / 2 }
    const projected = projectOntoRoute(midway, route)
    // Le point retombe sur le segment, a mi-chemin environ.
    expect(projected.lat).toBeGreaterThan(COTONOU.lat)
    expect(projected.lat).toBeLessThan(BOHICON.lat)
    expect(remainingDistanceKm(projected, midway)).toBeLessThan(6)
  })
})
