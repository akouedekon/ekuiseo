import { useQuery } from '@tanstack/react-query'
import { useMemo, useSyncExternalStore } from 'react'
import { apiClient } from '@/api/client'
import type { GeoPlaceResponse } from '@/api/extended'
import { FALLBACK_PLACES, SAME_PLACE_KM, haversineKm, normalize, searchPlaces, shortName, type CityOption } from '@/lib/cities'
import { readRecentPlaces } from '@/lib/recentPlaces'

/** Cle persistee (racine `geo`, liste blanche de lib/queryClient.ts) : le referentiel survit au redemarrage hors ligne. */
export const GEO_PLACES_KEY = ['geo', 'places'] as const

/** Libelle transmis a l'API et affiche : « Agla — Cotonou » pour un quartier, la ville seule sinon (audit F422). */
export function toCityOption(place: GeoPlaceResponse): CityOption {
  const parentName = place.parentName ?? null
  return {
    id: place.id,
    label: parentName ? `${place.name} — ${parentName}` : place.name,
    lat: place.lat,
    lng: place.lng,
    region: place.region ?? (place.countryCode && place.countryCode !== 'BJ' ? place.countryCode : 'Bénin'),
    kind: place.kind,
    parentName,
  }
}

/**
 * Referentiel complet des lieux (GET /api/v1/geo/places, public, cacheable 24 h) :
 * charge une fois, persiste, et seule source des villes de l'autocompletion
 * (audit F411). Tant qu'il n'est pas arrive (premiere ouverture hors ligne), la
 * liste de repli de lib/cities.ts sert a sa place.
 */
export function useGeoPlaces() {
  return useQuery<GeoPlaceResponse[], Error, CityOption[]>({
    queryKey: GEO_PLACES_KEY,
    queryFn: ({ signal }) => apiClient.get<GeoPlaceResponse[]>('/api/v1/geo/places', { auth: false, signal }),
    select: (places) => places.map(toCityOption),
    staleTime: 24 * 60 * 60 * 1000,
    gcTime: 7 * 24 * 60 * 60 * 1000,
  })
}

/** Deux suggestions sont le meme lieu : meme identifiant serveur, ou meme nom normalise a moins de 500 m. */
function samePlace(a: CityOption, b: CityOption): boolean {
  if (a.id && b.id) return a.id === b.id
  return normalize(shortName(a)) === normalize(shortName(b)) && haversineKm(a.lat, a.lng, b.lat, b.lng) < SAME_PLACE_KM
}

export function dedupePlaces(candidates: CityOption[]): CityOption[] {
  const kept: CityOption[] = []
  for (const candidate of candidates) {
    if (!kept.some((existing) => samePlace(existing, candidate))) kept.push(candidate)
  }
  return kept
}

/*
 * Historique des dernieres villes recherchees (localStorage). Le composant se
 * re-rend quand `rememberPlace` a ecrit (evenement `storage` entre onglets, et
 * un evenement maison dans le meme onglet) ; en pratique la liste est relue a
 * chaque ouverture du champ.
 */
function subscribeRecents(onChange: () => void) {
  window.addEventListener('storage', onChange)
  window.addEventListener('ekuiseo:recent-places', onChange)
  return () => {
    window.removeEventListener('storage', onChange)
    window.removeEventListener('ekuiseo:recent-places', onChange)
  }
}
let recentsSnapshot: { raw: string; value: CityOption[] } = { raw: '', value: [] }
function getRecentsSnapshot(): CityOption[] {
  let raw = ''
  try {
    raw = localStorage.getItem('ekuiseo.recentPlaces') ?? ''
  } catch {
    raw = ''
  }
  if (raw !== recentsSnapshot.raw) recentsSnapshot = { raw, value: readRecentPlaces() }
  return recentsSnapshot.value
}
const NO_RECENTS: CityOption[] = []

export function useRecentPlaces(): CityOption[] {
  return useSyncExternalStore(subscribeRecents, getRecentsSnapshot, () => NO_RECENTS)
}

/**
 * Suggestions de villes et quartiers pour l'autocompletion, calculees localement
 * sur le referentiel serveur (instantane, hors ligne compris) : champ vide ->
 * dernieres villes recherchees puis villes principales ; saisie -> recherche
 * tolerante (accents, alias, ville de rattachement). Jamais de doublon (audit F411).
 */
export function useCitySuggestions(query: string, limit = 7): { suggestions: CityOption[]; recentCount: number } {
  const places = useGeoPlaces()
  const recents = useRecentPlaces()
  const trimmed = query.trim()
  const source = places.data ?? FALLBACK_PLACES

  return useMemo(() => {
    if (!trimmed) {
      const recentOnes = dedupePlaces(recents).slice(0, limit)
      const rest = dedupePlaces([...recentOnes, ...searchPlaces(source, '', limit)]).slice(0, limit)
      return { suggestions: rest, recentCount: Math.min(recentOnes.length, rest.length) }
    }
    return { suggestions: dedupePlaces(searchPlaces(source, trimmed, limit + 2)).slice(0, limit), recentCount: 0 }
  }, [trimmed, source, recents, limit])
}
