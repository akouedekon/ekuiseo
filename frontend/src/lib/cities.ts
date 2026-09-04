/** Villes/quartiers courants pour le covoiturage au Benin (et Lome, transfrontalier). */
export interface CityOption {
  label: string
  lat: number
  lng: number
  /** Departement ou pays, affiche en second plan dans l'autocompletion. */
  region: string
  /** Formes alternatives saisies par les usagers (sans accents, surnoms). */
  aliases?: string[]
}

export const BENIN_CITIES: CityOption[] = [
  { label: 'Cotonou', region: 'Littoral', lat: 6.3703, lng: 2.3912, aliases: ['coto'] },
  { label: 'Cotonou — Dantokpa', region: 'Littoral', lat: 6.3654, lng: 2.4278, aliases: ['dantokpa', 'tokpa'] },
  { label: 'Cotonou — Godomey', region: 'Atlantique', lat: 6.3667, lng: 2.3333, aliases: ['godomey'] },
  { label: 'Cotonou — Fidjrosse', region: 'Littoral', lat: 6.3567, lng: 2.3608, aliases: ['fidjrosse'] },
  { label: 'Abomey-Calavi', region: 'Atlantique', lat: 6.4489, lng: 2.3556, aliases: ['calavi'] },
  { label: 'Porto-Novo', region: 'Oueme', lat: 6.4969, lng: 2.6289, aliases: ['portonovo', 'hogbonou'] },
  { label: 'Seme-Podji', region: 'Oueme', lat: 6.3667, lng: 2.6333, aliases: ['seme'] },
  { label: 'Ouidah', region: 'Atlantique', lat: 6.3667, lng: 2.0853 },
  { label: 'Allada', region: 'Atlantique', lat: 6.6656, lng: 2.1514 },
  { label: 'Bohicon', region: 'Zou', lat: 7.1782, lng: 2.0667 },
  { label: 'Abomey', region: 'Zou', lat: 7.1826, lng: 1.9912 },
  { label: 'Covè', region: 'Zou', lat: 7.2214, lng: 2.3406, aliases: ['cove'] },
  { label: 'Lokossa', region: 'Mono', lat: 6.6389, lng: 1.7167 },
  { label: 'Comè', region: 'Mono', lat: 6.4056, lng: 1.8817, aliases: ['come'] },
  { label: 'Aplahoué', region: 'Couffo', lat: 6.9333, lng: 1.6833, aliases: ['aplahoue'] },
  { label: 'Dassa-Zoumè', region: 'Collines', lat: 7.7503, lng: 2.1836, aliases: ['dassa', 'dassa-zoume'] },
  { label: 'Savalou', region: 'Collines', lat: 7.9281, lng: 1.9756 },
  { label: 'Savè', region: 'Collines', lat: 8.0342, lng: 2.4864, aliases: ['save'] },
  { label: 'Parakou', region: 'Borgou', lat: 9.3372, lng: 2.6303 },
  { label: 'Tchaourou', region: 'Borgou', lat: 8.8867, lng: 2.5975 },
  { label: 'Nikki', region: 'Borgou', lat: 9.9401, lng: 3.2108 },
  { label: 'Djougou', region: 'Donga', lat: 9.7086, lng: 1.6661 },
  { label: 'Natitingou', region: 'Atacora', lat: 10.3042, lng: 1.3792, aliases: ['nati'] },
  { label: 'Tanguiéta', region: 'Atacora', lat: 10.6222, lng: 1.2653, aliases: ['tanguieta'] },
  { label: 'Kandi', region: 'Alibori', lat: 11.1342, lng: 2.9386 },
  { label: 'Malanville', region: 'Alibori', lat: 11.8681, lng: 3.3831 },
  { label: 'Lomé (Togo)', region: 'Togo', lat: 6.1319, lng: 1.2228, aliases: ['lome', 'togo'] },
  { label: 'Lagos (Nigéria)', region: 'Nigéria', lat: 6.5244, lng: 3.3792, aliases: ['lagos', 'nigeria'] },
  { label: 'Niamey (Niger)', region: 'Niger', lat: 13.5116, lng: 2.1254, aliases: ['niamey', 'niger'] },
]

/** Normalise pour comparer sans accents ni casse (saisie mobile rapide). */
export function normalize(value: string): string {
  return value
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .trim()
}

/** Recherche tolerante : prefixe prioritaire, puis inclusion, puis alias. */
export function searchCities(query: string, limit = 6): CityOption[] {
  const q = normalize(query)
  if (!q) return BENIN_CITIES.slice(0, limit)
  const scored = BENIN_CITIES.map((city) => {
    const label = normalize(city.label)
    const aliasHit = city.aliases?.some((a) => normalize(a).startsWith(q)) ?? false
    let score = -1
    if (label.startsWith(q)) score = 0
    else if (aliasHit) score = 1
    else if (label.includes(q)) score = 2
    else if (normalize(city.region).startsWith(q)) score = 3
    return { city, score }
  }).filter((entry) => entry.score >= 0)
  scored.sort((a, b) => a.score - b.score || a.city.label.localeCompare(b.city.label, 'fr'))
  return scored.slice(0, limit).map((entry) => entry.city)
}

export function findCityByLabel(label: string): CityOption | undefined {
  const target = normalize(label)
  return BENIN_CITIES.find((city) => normalize(city.label) === target)
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

/**
 * Prix conseille par place, indicatif : base kilometrique majoree de 15 %
 * pour la sinuosite reelle du reseau, arrondie au multiple de 500 FCFA.
 * TODO(backend) : remplacer par GET /api/v1/trips/price-suggestion quand il existera.
 */
export function suggestPricePerSeat(distanceKm: number): number {
  const raw = Math.max(500, distanceKm * 1.15 * 28)
  return Math.round(raw / 500) * 500
}

/** Duree de route estimee (55 km/h de moyenne, routes beninoises). */
export function estimateDurationMinutes(distanceKm: number): number {
  return Math.max(15, Math.round((distanceKm * 1.15) / 55 * 60))
}
