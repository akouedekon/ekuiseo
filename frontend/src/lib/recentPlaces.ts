import type { CityOption } from '@/lib/cities'

/*
 * Dernieres villes recherchees (audit, section 5 #21) : cinq entrees dans
 * localStorage, proposees en tete de l'autocompletion quand le champ est vide.
 * Confort de saisie uniquement : rien ici ne fait foi, et l'absence de stockage
 * (navigation privee) ne change rien au fonctionnement.
 */

const STORAGE_KEY = 'ekuiseo.recentPlaces'
export const MAX_RECENT_PLACES = 5

type Stored = Pick<CityOption, 'id' | 'label' | 'lat' | 'lng' | 'region' | 'kind' | 'parentName'>

function read(): Stored[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter(
      (item): item is Stored =>
        typeof item === 'object' &&
        item !== null &&
        typeof (item as Stored).label === 'string' &&
        Number.isFinite((item as Stored).lat) &&
        Number.isFinite((item as Stored).lng),
    )
  } catch {
    return []
  }
}

export function readRecentPlaces(): CityOption[] {
  return read().map((item) => ({ ...item, region: item.region ?? '' }))
}

/** Memorise un lieu en tete de liste (dedoublonne sur le libelle), cinq au plus. */
export function rememberPlace(city: CityOption): void {
  try {
    const entry: Stored = {
      id: city.id,
      label: city.label,
      lat: city.lat,
      lng: city.lng,
      region: city.region,
      kind: city.kind,
      parentName: city.parentName ?? null,
    }
    const next = [entry, ...read().filter((item) => item.label !== city.label)].slice(0, MAX_RECENT_PLACES)
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
    // `storage` ne se declenche pas dans l'onglet qui ecrit : on previent useRecentPlaces a la main.
    window.dispatchEvent(new Event('ekuiseo:recent-places'))
  } catch {
    /* stockage indisponible : pas d'historique, l'autocompletion reste complete */
  }
}
