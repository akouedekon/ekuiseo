import { QueryClient, type Query } from '@tanstack/react-query'
import { createSyncStoragePersister } from '@tanstack/query-sync-storage-persister'
import type { PersistedClient, Persister, PersistQueryClientOptions } from '@tanstack/react-query-persist-client'
import { isTransientError } from '@/lib/errors'

const PERSIST_KEY = 'ekuiseo-query-cache'
/** Proprietaire du cache persiste (id utilisateur) : un autre compte ne rehydrate jamais ce cache. */
const CACHE_OWNER_KEY = 'ekuiseo.cacheOwner'
/** A incrementer quand la forme des donnees persistees change. */
const CACHE_VERSION = 'v3'
/**
 * Nom du cache runtime du service worker pour les lectures API publiques.
 * Doit rester identique a `cacheName` dans vite.config.ts (runtimeCaching).
 */
export const API_CACHE_NAME = 'ekuiseo-api'

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

/* ------------------------------------------------- Liste blanche de persistance */

/**
 * Seules les donnees publiques utiles hors ligne sont ecrites dans localStorage :
 * recherche et detail de trajet, axes populaires, referentiel geographique,
 * profils publics. Tout ce qui est personnel (`me`, `bookings`, `notifications`,
 * `payments`, `conversations`, `account`) ou reserve a l'administration (`admin`)
 * reste en memoire uniquement.
 */
const PERSISTED_ROOTS: ReadonlySet<string> = new Set(['trips', 'geo', 'users'])
/** Sous-cles de `trips` qui portent des donnees personnelles (liste d'appel, devis du passager). */
const PRIVATE_TRIP_SUBKEYS: ReadonlySet<string> = new Set(['passengers', 'quote'])

export function isPersistableQueryKey(queryKey: readonly unknown[]): boolean {
  const root = queryKey[0]
  if (typeof root !== 'string' || !PERSISTED_ROOTS.has(root)) return false
  if (root === 'trips') {
    return !queryKey.some((part) => typeof part === 'string' && PRIVATE_TRIP_SUBKEYS.has(part))
  }
  return true
}

/** Critere de deshydratation : requete reussie ET cle en liste blanche. */
export function shouldPersistQuery(query: Pick<Query, 'queryKey' | 'state'>): boolean {
  return query.state.status === 'success' && isPersistableQueryKey(query.queryKey)
}

/* ------------------------------------------------- Proprietaire du cache */

function readStorage(): Storage | null {
  try {
    return window.localStorage
  } catch {
    return null
  }
}

export function readCacheOwner(): string | null {
  try {
    return readStorage()?.getItem(CACHE_OWNER_KEY) ?? null
  } catch {
    return null
  }
}

export function writeCacheOwner(userId: string | null): void {
  try {
    const storage = readStorage()
    if (!storage) return
    if (userId) storage.setItem(CACHE_OWNER_KEY, userId)
    else storage.removeItem(CACHE_OWNER_KEY)
  } catch {
    /* stockage indisponible : pas de persistance, donc rien a cloisonner */
  }
}

/** Jeton d'invalidation effectif : version des donnees + compte proprietaire. */
export function cacheBuster(): string {
  return `${CACHE_VERSION}:${readCacheOwner() ?? 'anon'}`
}

/* ------------------------------------------------- Persister */

/**
 * Persister localStorage enveloppe pour cloisonner le cache par compte : chaque
 * ecriture est estampillee du proprietaire courant, et une lecture dont
 * l'estampille ne correspond pas est jetee au lieu d'etre rehydratee. Le
 * `buster` vu par TanStack reste la seule version des donnees (CACHE_VERSION).
 */
export function wrapPersisterWithOwner(inner: Persister): Persister {
  return {
    persistClient: (client: PersistedClient) => inner.persistClient({ ...client, buster: cacheBuster() }),
    restoreClient: async () => {
      const client = await inner.restoreClient()
      if (!client) return undefined
      if (client.buster !== cacheBuster()) {
        await inner.removeClient()
        return undefined
      }
      return { ...client, buster: CACHE_VERSION }
    },
    removeClient: () => inner.removeClient(),
  }
}

export function createPersister(): Persister | null {
  try {
    return wrapPersisterWithOwner(
      createSyncStoragePersister({
        storage: window.localStorage,
        key: PERSIST_KEY,
        throttleTime: 1000,
      }),
    )
  } catch {
    // localStorage indisponible (navigation privee, etc.) : pas de persistance,
    // l'app fonctionne quand meme avec le cache en memoire uniquement.
    return null
  }
}

/** Options du PersistQueryClientProvider : liste blanche, duree de vie, version. */
export function createPersistOptions(persister: Persister): Omit<PersistQueryClientOptions, 'queryClient'> {
  return {
    persister,
    maxAge: 24 * 60 * 60 * 1000,
    buster: CACHE_VERSION,
    dehydrateOptions: { shouldDehydrateQuery: shouldPersistQuery },
  }
}

/* ------------------------------------------------- Purges */

/** Efface immediatement le cache persiste et son proprietaire : deconnexion, expiration, changement de compte. */
export function clearPersistedCache(): void {
  try {
    const storage = readStorage()
    storage?.removeItem(PERSIST_KEY)
    storage?.removeItem(CACHE_OWNER_KEY)
  } catch {
    /* ignore */
  }
}

/**
 * Vide le cache runtime du service worker (reponses API). `caches` peut etre
 * absent (navigateur ancien, contexte non securise) ou refuser : on ignore.
 */
export async function clearApiCache(): Promise<void> {
  try {
    if (typeof caches === 'undefined') return
    await caches.delete(API_CACHE_NAME)
  } catch {
    /* ignore */
  }
}
