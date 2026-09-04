import { QueryClient } from '@tanstack/react-query'
import { createSyncStoragePersister } from '@tanstack/query-sync-storage-persister'
import { isTransientError } from '@/lib/errors'

const PERSIST_KEY = 'ekuiseo-query-cache'

/**
 * Configuration adaptee a une connectivite mobile irreguliere (contexte
 * beninois) : on reessaie uniquement quand cela peut changer quelque chose
 * (reseau, serveur momentanement en panne), jamais sur une erreur definitive
 * (403, 404, 409...), et on persiste le cache dans localStorage pour un
 * affichage instantane (potentiellement perime) au demarrage hors-ligne.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => failureCount < 2 && isTransientError(error),
      retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8_000),
      staleTime: 60_000,
      gcTime: 24 * 60 * 60 * 1000,
      refetchOnWindowFocus: false,
      networkMode: 'offlineFirst',
    },
    mutations: {
      retry: false,
      networkMode: 'offlineFirst',
    },
  },
})

export function createPersister() {
  try {
    return createSyncStoragePersister({
      storage: window.localStorage,
      key: PERSIST_KEY,
      throttleTime: 1000,
    })
  } catch {
    // localStorage indisponible (navigation privee, etc.) : pas de persistance,
    // l'app fonctionne quand meme avec le cache en memoire uniquement.
    return null
  }
}

/** Efface immediatement le cache persiste (donnees personnelles) : a la deconnexion. */
export function clearPersistedCache(): void {
  try {
    window.localStorage.removeItem(PERSIST_KEY)
  } catch {
    /* ignore */
  }
}
