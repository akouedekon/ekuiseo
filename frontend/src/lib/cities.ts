/*
 * Geographie cote client. Le referentiel des lieux est celui du serveur
 * (`geo_places`, GET /api/v1/geo/places, voir hooks/useGeo.ts) : la liste
 * ci-dessous n'est qu'un REPLI minimal, servi tant que ce referentiel n'est pas
 * charge (premiere ouverture hors ligne). Elle reprend les villes de la migration
 * V3, memes libelles et memes coordonnees, sans les quartiers.
 */

export type PlaceKind = 'CITY' | 'DISTRICT' | 'STATION'

export interface CityOption {
  /** Identifiant serveur ; absent pour une entree du repli ou un point saisi par ailleurs. */
  id?: string
  /** Libelle affiche et transmis a l'API (« Agla — Cotonou » pour un quartier). */
  label: string
  lat: number
  lng: number
  /** Departement ou pays, affiche en second plan dans l'autocompletion. */
  region: string
  kind?: PlaceKind
  /** Ville de rattachement d'un quartier ou d'une gare. */
  parentName?: string | null
  /** Formes alternatives saisies par les usagers (sans accents, surnoms). */
  aliases?: string[]
}

export const FALLBACK_PLACES: CityOption[] = [
  { label: 'Cotonou', region: 'Littoral', lat: 6.3703, lng: 2.3912, kind: 'CITY', aliases: ['coto'] },
  { label: 'Porto-Novo', region: 'Ouémé', lat: 6.4969, lng: 2.6289, kind: 'CITY', aliases: ['portonovo', 'hogbonou'] },
  { label: 'Abomey-Calavi', region: 'Atlantique', lat: 6.4489, lng: 2.3556, kind: 'CITY', aliases: ['calavi'] },
  { label: 'Bohicon', region: 'Zou', lat: 7.1781, lng: 2.0672, kind: 'CITY' },
  { label: 'Abomey', region: 'Zou', lat: 7.1826, lng: 1.991, kind: 'CITY' },
  { label: 'Parakou', region: 'Borgou', lat: 9.3372, lng: 2.6303, kind: 'CITY' },
  { label: 'Natitingou', region: 'Atacora', lat: 10.3042, lng: 1.3796, kind: 'CITY', aliases: ['nati'] },
  { label: 'Djougou', region: 'Donga', lat: 9.7085, lng: 1.6663, kind: 'CITY' },
  { label: 'Lokossa', region: 'Mono', lat: 6.6389, lng: 1.7169, kind: 'CITY' },
  { label: 'Ouidah', region: 'Atlantique', lat: 6.3626, lng: 2.0852, kind: 'CITY' },
  { label: 'Kandi', region: 'Alibori', lat: 11.1342, lng: 2.9386, kind: 'CITY' },
  { label: 'Malanville', region: 'Alibori', lat: 11.8636, lng: 3.3862, kind: 'CITY' },
  { label: 'Savalou', region: 'Collines', lat: 7.9285, lng: 1.9739, kind: 'CITY' },
  { label: 'Comè', region: 'Mono', lat: 6.4056, lng: 1.8836, kind: 'CITY', aliases: ['come'] },
  { label: 'Grand-Popo', region: 'Mono', lat: 6.2833, lng: 1.8167, kind: 'CITY' },
  { label: 'Lomé', region: 'Togo', lat: 6.1319, lng: 1.2228, kind: 'CITY', aliases: ['lome', 'togo'] },
  { label: 'Lagos', region: 'Nigéria', lat: 6.5244, lng: 3.3792, kind: 'CITY', aliases: ['nigeria'] },
]

/** Normalise pour comparer sans accents ni casse (saisie mobile rapide). */
export function normalize(value: string): string {
  return value
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .trim()
}

/** Nom court d'un lieu (sans la ville de rattachement), pour le classement et le dedoublonnage. */
export function shortName(city: CityOption): string {
  return city.parentName ? city.label.replace(new RegExp(` — ${city.parentName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`), '') : city.label
}

/**
 * Recherche tolerante dans une liste de lieux : prefixe prioritaire, puis
 * alias, puis inclusion, puis departement ou ville de rattachement. Sans
 * saisie, renvoie les premieres entrees (villes principales).
 */
export function searchPlaces(places: readonly CityOption[], query: string, limit = 6): CityOption[] {
  const q = normalize(query)
  if (!q) return places.slice(0, limit)
  const scored = places
    .map((city) => {
      const name = normalize(shortName(city))
      const aliasHit = city.aliases?.some((a) => normalize(a).startsWith(q)) ?? false
      let score = -1
      if (name.startsWith(q)) score = 0
      else if (aliasHit) score = 1
      else if (name.includes(q)) score = 2
      else if (normalize(city.region).startsWith(q) || (city.parentName && normalize(city.parentName).startsWith(q))) score = 3
      // Une ville passe avant ses quartiers a score egal.
      return { city, score: score < 0 ? score : score * 2 + (city.kind === 'CITY' || !city.kind ? 0 : 1) }
    })
    .filter((entry) => entry.score >= 0)
  scored.sort((a, b) => a.score - b.score || a.city.label.localeCompare(b.city.label, 'fr'))
  return scored.slice(0, limit).map((entry) => entry.city)
}

/** Distance orthodromique en km — sert au prix conseille et a la duree estimee. */
export function haversineKm(aLat: number, aLng: number, bLat: number, bLng: number): number {
  const R = 6371
  const dLat = ((bLat - aLat) * Math.PI) / 180
  const dLng = ((bLng - aLng) * Math.PI) / 180
  const lat1 = (aLat * Math.PI) / 180
  const lat2 = (bLat * Math.PI) / 180
  const h = Math.sin(dLat / 2) ** 2 + Math.sin(dLng / 2) ** 2 * Math.cos(lat1) * Math.cos(lat2)
  return 2 * R * Math.asin(Math.sqrt(h))
}

/** Deux lieux sont confondus (meme point) en deca de cette distance : un doublon, pas deux suggestions. */
export const SAME_PLACE_KM = 0.5

/** En deca, l'axe est urbain (Cotonou - Abomey-Calavi : 9,6 km) : rayon serre. */
const URBAN_AXIS_KM = 30
const URBAN_SEARCH_RADIUS_KM = 5
const INTERCITY_SEARCH_RADIUS_KM = 15
/** Un quartier ou une gare designe un point precis : rayon plus serre qu'une ville entiere (audit F422). */
const PRECISE_PLACE_RADIUS_KM = 4

/**
 * Rayon de recherche envoye au serveur, adapte a la longueur de l'axe et a la
 * nature des lieux : 4 km quand une extremite est un quartier ou une gare, 5 km
 * en urbain, 15 km en interurbain, et jamais plus de la moitie de la distance
 * origine-destination. Sans ce plafond, sur un axe court (Cotonou - Calavi), les
 * deux points cherches tombent dans le rayon des deux points de chaque trajet et
 * la recherche renvoie aussi les trajets en sens inverse (audit F408).
 */
export function searchRadiusKm(axisKm: number, kinds: { origin?: PlaceKind; destination?: PlaceKind } = {}): number {
  const precise = [kinds.origin, kinds.destination].some((kind) => kind === 'DISTRICT' || kind === 'STATION')
  const base = precise ? PRECISE_PLACE_RADIUS_KM : axisKm < URBAN_AXIS_KM ? URBAN_SEARCH_RADIUS_KM : INTERCITY_SEARCH_RADIUS_KM
  const capped = Math.min(base, axisKm / 2)
  // Au dixieme de km inferieur (le plafond reste strict), et jamais nul : deux lieux confondus gardent un rayon minimal.
  return Math.max(0.5, Math.floor(capped * 10) / 10)
}

/**
 * Prix conseille par place, indicatif : base kilometrique majoree de 15 %
 * pour la sinuosite reelle du reseau, arrondie au multiple de 500 FCFA.
 * TODO(backend) : remplacer par GET /api/v1/trips/price-suggestion quand il existera.
 */
export function suggestPricePerSeat(distanceKm: number): number {
  const raw = Math.max(500, distanceKm * 1.15 * 28)
  return Math.round(raw / 500) * 500
}

/** Sinuosite moyenne du reseau : la route fait ~15 % de plus que l'orthodromie. */
const ROAD_FACTOR = 1.15
/** Vitesse moyenne en zone urbaine (embouteillages de Cotonou, zemidjans, feux). */
const URBAN_SPEED_KMH = 25
/** Vitesse moyenne sur les routes nationales bitumees. */
const INTERCITY_SPEED_KMH = 55
/** Formalites a un poste frontiere (Hillacondji, Kraké, Malanville). */
const BORDER_CROSSING_MINUTES = 45

/**
 * Vrai si un point est hors du Benin : en dessous de 7° N, le pays s'etend de
 * Grand-Popo (1,6° E) a la frontiere nigeriane (2,75° E) ; au nord, de 0,77° E a
 * 3,85° E. Lome (1,22° E) et Lagos (3,38° E) tombent bien en dehors. Un libelle
 * portant un pays entre parentheses (« Lomé (Togo) ») tranche aussi.
 */
export function isOutsideBenin(lat: number, lng: number, label?: string): boolean {
  if (label && /\((togo|nig[ée]ria|niger|ghana|burkina)\)/i.test(label)) return true
  if (lat < 6.1 || lat > 12.45) return true
  if (lat < 7) return lng < 1.6 || lng > 2.75
  return lng < 0.77 || lng > 3.85
}

/**
 * Duree de route ESTIMEE, jamais un horaire ferme (audit F414). Modele a deux
 * vitesses : 25 km/h en deca de 30 km (axe urbain : Cotonou - Calavi, Cotonou -
 * Porto-Novo aux heures de pointe), 55 km/h au-dela ; + 45 min par franchissement
 * de frontiere. Afficher le resultat avec le signe « ≈ » ou la mention « estimee ».
 */
export function estimateDurationMinutes(distanceKm: number, options: { crossBorder?: boolean } = {}): number {
  const roadKm = distanceKm * ROAD_FACTOR
  const speed = distanceKm < URBAN_AXIS_KM ? URBAN_SPEED_KMH : INTERCITY_SPEED_KMH
  const minutes = (roadKm / speed) * 60 + (options.crossBorder ? BORDER_CROSSING_MINUTES : 0)
  return Math.max(15, Math.round(minutes))
}
