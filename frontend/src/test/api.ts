import { QueryClient } from '@tanstack/react-query'
import { vi } from 'vitest'

/**
 * Double de `fetch` piloté par URL : chaque route déclare la réponse (JSON, statut)
 * ou une fonction qui la construit à partir de la requête. Une URL inconnue
 * répond 404 en RFC 7807, ce qui fait échouer proprement l'appel plutôt que de
 * laisser un test attendre indéfiniment.
 */
export type RouteHandler = (input: { method: string; url: string; body: unknown }) => { status?: number; body?: unknown }

export interface FakeApi {
  fetch: ReturnType<typeof vi.fn<typeof fetch>>
  calls: () => { method: string; url: string; body: unknown }[]
}

export function jsonResponse(status: number, body: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
}

export function installFakeApi(routes: Record<string, RouteHandler | { status?: number; body?: unknown }>): FakeApi {
  const calls: { method: string; url: string; body: unknown }[] = []
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    const method = (init?.method ?? 'GET').toUpperCase()
    let body: unknown = undefined
    if (typeof init?.body === 'string') {
      try {
        body = JSON.parse(init.body)
      } catch {
        body = init.body
      }
    }
    calls.push({ method, url, body })
    const path = url.replace(/^https?:\/\/[^/]+/, '')
    const key = Object.keys(routes).find((candidate) => {
      const [routeMethod, routePath] = candidate.includes(' ') ? candidate.split(' ', 2) : ['GET', candidate]
      if (routeMethod !== method) return false
      return routePath.endsWith('*') ? path.startsWith(routePath.slice(0, -1)) : path === routePath || path.split('?')[0] === routePath
    })
    if (!key) return jsonResponse(404, { status: 404, title: 'Not Found', detail: `Aucune route de test pour ${method} ${path}` })
    const route = routes[key]
    const result = typeof route === 'function' ? route({ method, url, body }) : route
    return jsonResponse(result.status ?? 200, result.body)
  })
  vi.stubGlobal('fetch', fetchMock)
  return { fetch: fetchMock, calls: () => calls }
}

/** Client de requêtes sans réessai ni cache persistant : chaque test part de zéro. */
export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0, networkMode: 'offlineFirst' },
      mutations: { retry: false, networkMode: 'offlineFirst' },
    },
  })
}
