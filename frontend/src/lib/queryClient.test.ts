import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PersistedClient, Persister } from '@tanstack/react-query-persist-client'
import { authStore } from '@/api/client'
import { resetSession } from '@/hooks/useAuth'
import {
  API_CACHE_NAME,
  cacheBuster,
  clearPersistedCache,
  isPersistableQueryKey,
  queryClient,
  readCacheOwner,
  shouldPersistQuery,
  wrapPersisterWithOwner,
  writeCacheOwner,
} from './queryClient'

/** localStorage minimal : l'environnement de test est Node, sans DOM. */
function fakeStorage(): Storage {
  const map = new Map<string, string>()
  return {
    get length() {
      return map.size
    },
    clear: () => map.clear(),
    getItem: (key) => map.get(key) ?? null,
    key: (index) => [...map.keys()][index] ?? null,
    removeItem: (key) => {
      map.delete(key)
    },
    setItem: (key, value) => {
      map.set(key, value)
    },
  }
}

describe('liste blanche de persistance', () => {
  it.each([
    [['trips', 'search', { originLat: 1 }], true],
    [['trips', 'search', 'pages', { originLat: 1 }], true],
    [['trips', 'popular', 4], true],
    [['trips', 'abc'], true],
    [['trips', 'abc', 'stops'], true],
    [['geo', 'search', 'coto'], true],
    [['users', 'u1'], true],
    [['users', 'u1', 'reviews'], true],
  ])('persiste la cle publique %j', (key, expected) => {
    expect(isPersistableQueryKey(key)).toBe(expected)
  })

  it.each([
    [['me']],
    [['me', 'conversations']],
    [['me', 'payment-methods']],
    [['bookings']],
    [['bookings', 'b1', 'messages']],
    [['notifications']],
    [['payments', 'p1']],
    [['admin', 'users', 'jean']],
    [['admin', 'verifications', 'PENDING']],
    [['conversations']],
    [['account']],
    // Sous-cles personnelles de `trips` : liste d'appel du conducteur, devis du passager.
    [['trips', 't1', 'passengers']],
    [['trips', 't1', 'quote', 2, '', 'MOMO_DEPOSIT', 5000]],
    [[]],
    [[42]],
  ])('ne persiste jamais la cle personnelle %j', (key) => {
    expect(isPersistableQueryKey(key)).toBe(false)
  })

  it("ne deshydrate qu'une requete reussie", () => {
    const success = { queryKey: ['trips', 'popular'], state: { status: 'success' } }
    const pending = { queryKey: ['trips', 'popular'], state: { status: 'pending' } }
    const error = { queryKey: ['trips', 'popular'], state: { status: 'error' } }
    expect(shouldPersistQuery(success as never)).toBe(true)
    expect(shouldPersistQuery(pending as never)).toBe(false)
    expect(shouldPersistQuery(error as never)).toBe(false)
    expect(shouldPersistQuery({ queryKey: ['me'], state: { status: 'success' } } as never)).toBe(false)
  })
})

describe('cloisonnement du cache par compte', () => {
  let storage: Storage

  beforeEach(() => {
    storage = fakeStorage()
    vi.stubGlobal('window', { localStorage: storage })
    vi.stubGlobal('localStorage', storage)
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function persistedClient(buster: string): PersistedClient {
    return { timestamp: Date.now(), buster, clientState: { queries: [], mutations: [] } }
  }

  function innerPersister(stored: PersistedClient | undefined) {
    const state = { stored }
    const inner: Persister = {
      persistClient: vi.fn((client: PersistedClient) => {
        state.stored = client
      }),
      restoreClient: vi.fn(() => state.stored),
      removeClient: vi.fn(() => {
        state.stored = undefined
      }),
    }
    return { inner, state }
  }

  it('estampille chaque ecriture du proprietaire courant', async () => {
    writeCacheOwner('user-a')
    const { inner, state } = innerPersister(undefined)
    const persister = wrapPersisterWithOwner(inner)
    await persister.persistClient(persistedClient('ignored'))
    expect(state.stored?.buster).toBe(cacheBuster())
    expect(state.stored?.buster).toContain('user-a')
  })

  it("rehydrate le cache du meme compte, sous la version de donnees attendue par TanStack", async () => {
    writeCacheOwner('user-a')
    const { inner } = innerPersister(persistedClient(cacheBuster()))
    const restored = await wrapPersisterWithOwner(inner).restoreClient()
    expect(restored).toBeDefined()
    expect(restored?.buster).not.toContain('user-a')
    expect(inner.removeClient).not.toHaveBeenCalled()
  })

  it("jette le cache d'un autre compte au lieu de le rehydrater", async () => {
    writeCacheOwner('user-a')
    const stale = persistedClient(cacheBuster())
    writeCacheOwner('user-b')
    const { inner } = innerPersister(stale)
    const restored = await wrapPersisterWithOwner(inner).restoreClient()
    expect(restored).toBeUndefined()
    expect(inner.removeClient).toHaveBeenCalledTimes(1)
  })

  it("jette le cache d'un compte quand plus personne n'est connecte", async () => {
    writeCacheOwner('user-a')
    const stale = persistedClient(cacheBuster())
    clearPersistedCache()
    expect(readCacheOwner()).toBeNull()
    const { inner } = innerPersister(stale)
    expect(await wrapPersisterWithOwner(inner).restoreClient()).toBeUndefined()
  })
})

describe('resetSession', () => {
  let storage: Storage
  const cachesDelete = vi.fn<(name: string) => Promise<boolean>>()

  beforeEach(() => {
    storage = fakeStorage()
    vi.stubGlobal('window', { localStorage: storage })
    vi.stubGlobal('localStorage', storage)
    cachesDelete.mockResolvedValue(true)
    vi.stubGlobal('caches', { delete: cachesDelete })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    queryClient.clear()
    authStore.clear('logout')
  })

  it('vide jetons, cache memoire, cache persiste, proprietaire et cache du service worker', async () => {
    authStore.setTokens('access', 'refresh')
    writeCacheOwner('user-a')
    storage.setItem('ekuiseo-query-cache', '{"buster":"v3:user-a"}')
    queryClient.setQueryData(['me'], { id: 'user-a' })
    queryClient.setQueryData(['bookings'], [{ id: 'b1' }])

    const reasons: string[] = []
    const unsubscribe = authStore.subscribe((_authenticated, reason) => reasons.push(reason))
    resetSession('expired')
    unsubscribe()

    expect(authStore.isAuthenticated()).toBe(false)
    expect(reasons).toEqual(['expired'])
    expect(queryClient.getQueryData(['me'])).toBeUndefined()
    expect(queryClient.getQueryData(['bookings'])).toBeUndefined()
    expect(storage.getItem('ekuiseo-query-cache')).toBeNull()
    expect(readCacheOwner()).toBeNull()
    await Promise.resolve()
    expect(cachesDelete).toHaveBeenCalledWith(API_CACHE_NAME)
  })

  it('tolere un navigateur sans Cache Storage', () => {
    vi.stubGlobal('caches', undefined)
    authStore.setTokens('access', 'refresh')
    expect(() => resetSession('logout')).not.toThrow()
    expect(authStore.isAuthenticated()).toBe(false)
  })
})
