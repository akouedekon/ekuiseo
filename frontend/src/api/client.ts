import type { AuthResponse, ProblemDetail } from './types'

const API_BASE_URL = (import.meta.env.VITE_API_URL as string | undefined) ?? 'http://localhost:8080'

const ACCESS_TOKEN_KEY = 'ekuiseo.accessToken'
const REFRESH_TOKEN_KEY = 'ekuiseo.refreshToken'

export const tokenStorage = {
  getAccessToken(): string | null {
    try {
      return localStorage.getItem(ACCESS_TOKEN_KEY)
    } catch {
      return null
    }
  },
  getRefreshToken(): string | null {
    try {
      return localStorage.getItem(REFRESH_TOKEN_KEY)
    } catch {
      return null
    }
  },
  setTokens(accessToken: string, refreshToken: string) {
    try {
      localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
      localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
    } catch {
      // Stockage indisponible (navigation privee, quota) : on continue en memoire
      // pour la session en cours, sans persistance entre rechargements.
    }
  },
  clear() {
    try {
      localStorage.removeItem(ACCESS_TOKEN_KEY)
      localStorage.removeItem(REFRESH_TOKEN_KEY)
    } catch {
      /* ignore */
    }
  },
}

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

interface RequestOptions {
  method?: string
  body?: unknown
  auth?: boolean
  signal?: AbortSignal
}

let refreshPromise: Promise<string | null> | null = null

async function refreshAccessToken(): Promise<string | null> {
  const refreshToken = tokenStorage.getRefreshToken()
  if (!refreshToken) return null
  if (!refreshPromise) {
    refreshPromise = fetch(`${API_BASE_URL}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    })
      .then(async (res) => {
        if (!res.ok) {
          tokenStorage.clear()
          return null
        }
        const data = (await res.json()) as AuthResponse
        tokenStorage.setTokens(data.accessToken, data.refreshToken)
        return data.accessToken
      })
      .catch(() => {
        tokenStorage.clear()
        return null
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

/**
 * Client HTTP typé minimal (fetch), avec injection du JWT, rafraîchissement
 * automatique en cas de 401, et parsing des erreurs RFC 7807 (ProblemDetail).
 * Concu pour un reseau irregulier : n'implemente pas de retry lui-meme (c'est
 * TanStack Query, cote hooks, qui gere les tentatives).
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
  const { method = 'GET', body, auth = true, signal } = options

  const doFetch = async (token: string | null): Promise<Response> => {
    const headers: Record<string, string> = {}
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (auth && token) headers['Authorization'] = `Bearer ${token}`
    return fetch(`${API_BASE_URL}${path}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal,
    })
  }

  let res = await doFetch(tokenStorage.getAccessToken())

  if (res.status === 401 && auth && tokenStorage.getRefreshToken()) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await doFetch(newToken)
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
  const res = await authorizedFetch(path)
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
