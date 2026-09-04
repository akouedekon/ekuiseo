import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { apiClient } from '@/api/client'
import { searchCities, type CityOption } from '@/lib/cities'
import type { GeoPlaceResponse } from '@/api/extended'

const DEBOUNCE_MS = 250
const MIN_QUERY_LENGTH = 2

/** Valeur retardee : l'API n'est interrogee qu'apres un court silence de saisie. */
function useDebouncedValue<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const id = window.setTimeout(() => setDebounced(value), delay)
    return () => window.clearTimeout(id)
  }, [value, delay])
  return debounced
}

function toCityOption(place: GeoPlaceResponse): CityOption {
  return {
    label: place.name,
    lat: place.lat,
    lng: place.lng,
    region: place.region ?? (place.countryCode === 'BJ' ? 'Bénin' : place.countryCode),
  }
}

/**
 * Suggestions de villes/quartiers pour l'autocompletion : le referentiel du
 * serveur (GET /api/v1/geo/search, migration V3) en premier, complete par la
 * liste locale (lib/cities.ts) qui reste la seule source hors ligne ou si
 * l'API ne repond pas. Jamais de doublon : un lieu connu des deux cotes n'est
 * propose qu'une fois, avec les coordonnees du serveur.
 */
export function useCitySuggestions(query: string, limit = 7): CityOption[] {
  const trimmed = query.trim()
  const debounced = useDebouncedValue(trimmed, DEBOUNCE_MS)

  const remote = useQuery({
    queryKey: ['geo', 'search', debounced.toLowerCase()],
    queryFn: () =>
      apiClient.get<GeoPlaceResponse[]>(`/api/v1/geo/search?q=${encodeURIComponent(debounced)}`, { auth: false }),
    enabled: debounced.length >= MIN_QUERY_LENGTH,
    staleTime: 10 * 60_000,
    // Une suggestion en retard ne sert a rien : pas de reessai, la liste locale prend le relais.
    retry: false,
  })

  const local = useMemo(() => searchCities(trimmed, limit), [trimmed, limit])

  return useMemo(() => {
    const fromApi = (remote.data ?? []).map(toCityOption)
    const seen = new Set(fromApi.map((c) => c.label.toLowerCase()))
    const merged = [...fromApi]
    for (const city of local) {
      if (!seen.has(city.label.toLowerCase())) merged.push(city)
    }
    return merged.slice(0, limit)
  }, [remote.data, local, limit])
}
