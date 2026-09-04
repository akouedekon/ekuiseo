import { QueryClient } from '@tanstack/react-query'
import { createSyncStoragePersister } from '@tanstack/query-sync-storage-persister'

/**
 * Configuration adaptee a une connectivite mobile irreguliere (contexte
 * beninois) : on tente plusieurs fois avant d'abandonner, on garde les
 * donnees en cache un peu plus longtemps que la valeur par defaut, et on les
 * persiste dans localStorage pour un affichage instantane (potentiellement
 * perime) au demarrage hors-ligne.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 3,
      retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 15_000),
      staleTime: 60_000,
      gcTime: 24 * 60 * 60 * 1000,
      refetchOnWindowFocus: false,
      networkMode: 'offlineFirst',
    },
    mutations: {
      retry: 1,
      networkMode: 'offlineFirst',
    },
  },
})

export function createPersister() {
  try {
    return createSyncStoragePersister({
      storage: window.localStorage,
      key: 'ekuiseo-query-cache',
      throttleTime: 1000,
    })
  } catch {
    // localStorage indisponible (navigation privee, etc.) : pas de persistance,
    // l'app fonctionne quand meme avec le cache en memoire uniquement.
    return null
  }
}
