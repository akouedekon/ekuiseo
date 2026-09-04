import type { AuthResponse, ProblemDetail } from './types'

/*
 * Base de l'API. Vide par defaut : les appels partent en relatif (`/api/v1/...`),
 * ce qui couvre la production (Caddy sert le front et proxifie /api) et le
 * developpement (proxy Vite vers le backend, voir vite.config.ts). Une URL
 * absolue n'est necessaire que pour un front heberge ailleurs que l'API.
 */
export const API_BASE_URL = ((import.meta.env.VITE_API_URL as string | undefined) ?? '').replace(/\/$/, '')

/** Delai au-dela duquel une requete est abandonnee (reseau mobile degrade compris). */
const REQUEST_TIMEOUT_MS = 20_000

const ACCESS_TOKEN_KEY = 'ekuiseo.accessToken'
const REFRESH_TOKEN_KEY = 'ekuiseo.refreshToken'

/* ------------------------------------------------------------ Session */

export type AuthChangeReason = 'login' | 'logout' | 'expired'
type AuthListener = (authenticated: boolean, reason: AuthChangeReason) => void

/*
 * Jetons en memoire d'abord, localStorage ensuite : si le stockage est
 * indisponible (navigation privee, quota), la session tient au moins le temps
 * de l'onglet. Tout changement est notifie pour que React (garde de route,
 * en-tete) reagisse immediatement, y compris a une expiration detectee au
 * milieu d'une requete.
 */
let memoryAccess: string | null = null
let memoryRefresh: string | null = null
const listeners = new Set<AuthListener>()

function readStorage(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function notify(reason: AuthChangeReason) {
  const authenticated = !!authStore.getAccessToken()
  for (const listener of listeners) listener(authenticated, reason)
}

export const authStore = {
  getAccessToken(): string | null {
    return memoryAccess ?? readStorage(ACCESS_TOKEN_KEY)
  },
  getRefreshToken(): string | null {
    return memoryRefresh ?? readStorage(REFRESH_TOKEN_KEY)
  },
  isAuthenticated(): boolean {
    return !!authStore.getAccessToken()
  },
  setTokens(accessToken: string, refreshToken: string, reason: AuthChangeReason = 'login') {
    memoryAccess = accessToken
    memoryRefresh = refreshToken
    try {
      localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
      localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
    } catch {
      /* stockage indisponible : la session reste en memoire pour cet onglet */
    }
    notify(reason)
  },
  clear(reason: AuthChangeReason = 'logout') {
    const wasAuthenticated = authStore.isAuthenticated()
    memoryAccess = null
    memoryRefresh = null
    try {
      localStorage.removeItem(ACCESS_TOKEN_KEY)
      localStorage.removeItem(REFRESH_TOKEN_KEY)
    } catch {
      /* ignore */
    }
    if (wasAuthenticated) notify(reason)
  },
  subscribe(listener: AuthListener): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
}

/** Alias historique, conserve pour les rares appels hors hooks. */
export const tokenStorage = authStore

/* ------------------------------------------------------------- Erreurs */

export class ApiError extends Error {
  status: number
  problem: ProblemDetail | null

  constructor(status: number, problem: ProblemDetail | null, fallbackMessage: string) {
    super(problem?.detail || problem?.title || fallbackMessage)
    this.status = status
    this.problem = problem
    this.name = 'ApiError'
  }
}

/** Echec avant toute reponse HTTP : hors ligne, delai depasse, serveur injoignable. */
export class NetworkError extends Error {
  kind: 'offline' | 'timeout' | 'unreachable'

  constructor(kind: NetworkError['kind'], message: string) {
    super(message)
    this.kind = kind
    this.name = 'NetworkError'
  }
}

interface RequestOptions {
  method?: string
  body?: unknown
  auth?: boolean
  signal?: AbortSignal
  timeoutMs?: number
}

let refreshPromise: Promise<string | null> | null = null

async function refreshAccessToken(): Promise<string | null> {
  const refreshToken = authStore.getRefreshToken()
  if (!refreshToken) return null
  if (!refreshPromise) {
    refreshPromise = fetch(`${API_BASE_URL}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    })
      .then(async (res) => {
        if (!res.ok) {
          // Jeton refuse par le serveur : la session est bel et bien terminee.
          authStore.clear('expired')
          return null
        }
        const data = (await res.json()) as AuthResponse
        authStore.setTokens(data.accessToken, data.refreshToken, 'login')
        return data.accessToken
      })
      .catch(() => null) // reseau : on ne conclut rien, l'appel initial remontera son erreur
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

function withTimeout(signal: AbortSignal | undefined, timeoutMs: number): AbortSignal {
  const timeout = AbortSignal.timeout(timeoutMs)
  if (!signal) return timeout
  if (typeof AbortSignal.any === 'function') return AbortSignal.any([signal, timeout])
  return signal
}

function toNetworkError(error: unknown): NetworkError {
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    return new NetworkError('offline', 'Vous êtes hors ligne.')
  }
  if (error instanceof DOMException && (error.name === 'TimeoutError' || error.name === 'AbortError')) {
    return new NetworkError('timeout', 'Le serveur met trop de temps à répondre.')
  }
  return new NetworkError('unreachable', 'Impossible de joindre le serveur.')
}

/**
 * Client HTTP typé (fetch) : JWT injecte, rafraichissement automatique sur 401
 * (une seule fois, partage entre appels concurrents), delai maximal par
 * requete, erreurs RFC 7807 typees. Aucun reessai ici : c'est TanStack Query
 * qui decide, selon la nature de l'erreur (voir lib/queryClient.ts).
 */
async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const res = await authorizedFetch(path, options)
  if (!res.ok) throw await toApiError(res)

  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

/** Appel brut avec JWT et rafraichissement automatique en cas de 401 ; ne lit pas le corps. */
async function authorizedFetch(path: string, options: RequestOptions = {}): Promise<Response> {
  const { method = 'GET', body, auth = true, signal, timeoutMs = REQUEST_TIMEOUT_MS } = options

  const doFetch = async (token: string | null): Promise<Response> => {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (auth && token) headers['Authorization'] = `Bearer ${token}`
    try {
      return await fetch(`${API_BASE_URL}${path}`, {
        method,
        headers,
        body: body !== undefined ? JSON.stringify(body) : undefined,
        signal: withTimeout(signal, timeoutMs),
      })
    } catch (error) {
      throw toNetworkError(error)
    }
  }

  const token = authStore.getAccessToken()
  let res = await doFetch(token)

  if (res.status === 401 && auth && token) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await doFetch(newToken)
    } else if (!authStore.getRefreshToken()) {
      // Pas de jeton de rafraichissement : la session est terminee.
      authStore.clear('expired')
    }
  }
  return res
}

async function toApiError(res: Response): Promise<ApiError> {
  let problem: ProblemDetail | null = null
  try {
    problem = (await res.json()) as ProblemDetail
  } catch {
    /* corps non JSON (ex: erreur reseau/proxy) */
  }
  return new ApiError(res.status, problem, `Erreur HTTP ${res.status}`)
}

/**
 * Telecharge un fichier servi par l'API (ex. export CSV du back-office) avec le
 * meme JWT et la meme gestion du 401 que les appels JSON, puis le remet au
 * navigateur sous `filename`. Un simple lien <a href> ne pourrait pas porter
 * l'en-tete Authorization.
 */
export async function downloadFile(path: string, filename: string): Promise<void> {
  const res = await authorizedFetch(path, { timeoutMs: 60_000 })
  if (!res.ok) throw await toApiError(res)
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.rel = 'noopener'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  // Laisse au navigateur le temps de demarrer le telechargement avant de liberer l'URL.
  setTimeout(() => URL.revokeObjectURL(url), 10_000)
}

export const apiClient = {
  get: <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'POST', body }),
  patch: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'PATCH', body }),
  delete: <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'DELETE' }),
}
