import { ApiError, NetworkError, apiClient, authStore } from './client'
import type { ProblemDetail } from './types'

/*
 * Appels bruts avec le JWT de la session, pour ce que le client JSON (`apiClient`)
 * ne couvre pas : multipart avec progression (televersement des pieces d'identite),
 * DELETE avec corps (abonnement push), lecture d'un flux binaire (piece d'identite
 * cote back-office, affichee via un blob : une URL nue dans <img> ne porterait pas
 * l'en-tete Authorization).
 *
 * Meme base d'URL que client.ts (relative par defaut, voir VITE_API_URL). Sur un 401,
 * le rafraichissement du jeton est confie au client standard (un GET leger declenche
 * sa logique de refresh, partagee entre appels concurrents), puis l'appel est rejoue
 * une fois avec le nouveau jeton.
 */
const API_BASE_URL = ((import.meta.env.VITE_API_URL as string | undefined) ?? '').replace(/\/$/, '')
const DEFAULT_TIMEOUT_MS = 20_000

/** Force le rafraichissement du jeton via le client standard ; vrai si un nouveau jeton est disponible. */
async function refreshThroughClient(previousToken: string | null): Promise<boolean> {
  // Le refresh token vit dans un cookie HttpOnly : on ne peut pas savoir d avance s il
  // existe, le client standard tente le rafraichissement et echoue proprement sinon.
  try {
    await apiClient.get<unknown>('/api/v1/me/preferences')
  } catch {
    /* l echec eventuel de ce GET n importe pas : seul le jeton compte */
  }
  const token = authStore.getAccessToken()
  return !!token && token !== previousToken
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

/** Reponse non-2xx -> ApiError portant le ProblemDetail RFC 7807 quand il est lisible. */
export async function toApiError(res: Response): Promise<ApiError> {
  let problem: ProblemDetail | null = null
  try {
    problem = (await res.json()) as ProblemDetail
  } catch {
    /* corps non JSON */
  }
  return new ApiError(res.status, problem, `Erreur HTTP ${res.status}`)
}

/** `fetch` avec JWT, delai et un rejeu apres rafraichissement sur 401. Ne lit pas le corps. */
export async function authorizedRawFetch(path: string, init: RequestInit & { timeoutMs?: number } = {}): Promise<Response> {
  const { timeoutMs = DEFAULT_TIMEOUT_MS, headers, ...rest } = init
  const attempt = async (token: string | null): Promise<Response> => {
    const merged = new Headers(headers)
    if (token) merged.set('Authorization', `Bearer ${token}`)
    try {
      return await fetch(`${API_BASE_URL}${path}`, { ...rest, headers: merged, signal: AbortSignal.timeout(timeoutMs) })
    } catch (error) {
      throw toNetworkError(error)
    }
  }
  const token = authStore.getAccessToken()
  let res = await attempt(token)
  if (res.status === 401 && token && (await refreshThroughClient(token))) {
    res = await attempt(authStore.getAccessToken())
  }
  return res
}

/** Lit un flux binaire authentifie (image, PDF) ; ApiError sur un statut d'erreur. */
export async function fetchAuthorizedBlob(path: string): Promise<Blob> {
  const res = await authorizedRawFetch(path, { headers: { Accept: '*/*' }, timeoutMs: 60_000 })
  if (!res.ok) throw await toApiError(res)
  return res.blob()
}

/**
 * Envoi multipart avec progression (XMLHttpRequest : `fetch` n'expose pas la
 * progression d'envoi). Meme JWT, meme rejeu sur 401, memes erreurs typees.
 */
export function uploadAuthorized<T>(
  path: string,
  form: FormData,
  onProgress?: (fraction: number) => void,
  timeoutMs = 120_000,
): Promise<T> {
  const send = (token: string | null): Promise<{ status: number; body: string }> =>
    new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest()
      xhr.open('POST', `${API_BASE_URL}${path}`)
      xhr.timeout = timeoutMs
      xhr.setRequestHeader('Accept', 'application/json')
      if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`)
      xhr.upload.onprogress = (event) => {
        if (onProgress && event.lengthComputable && event.total > 0) onProgress(Math.min(1, event.loaded / event.total))
      }
      xhr.onload = () => resolve({ status: xhr.status, body: xhr.responseText })
      xhr.onerror = () => reject(new NetworkError(navigator.onLine === false ? 'offline' : 'unreachable', 'Impossible de joindre le serveur.'))
      xhr.ontimeout = () => reject(new NetworkError('timeout', 'Le serveur met trop de temps à répondre.'))
      xhr.onabort = () => reject(new NetworkError('unreachable', 'Envoi interrompu.'))
      xhr.send(form)
    })

  const parse = (status: number, body: string): T => {
    if (status >= 200 && status < 300) return (body ? JSON.parse(body) : undefined) as T
    let problem: ProblemDetail | null = null
    try {
      problem = body ? (JSON.parse(body) as ProblemDetail) : null
    } catch {
      /* corps non JSON */
    }
    throw new ApiError(status, problem, `Erreur HTTP ${status}`)
  }

  return (async () => {
    const token = authStore.getAccessToken()
    let result = await send(token)
    if (result.status === 401 && token && (await refreshThroughClient(token))) {
      onProgress?.(0)
      result = await send(authStore.getAccessToken())
    }
    return parse(result.status, result.body)
  })()
}
