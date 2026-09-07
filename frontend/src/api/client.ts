import type { AuthResponse, ProblemDetail } from './types'

/*
 * Base de l'API. Vide par defaut : les appels partent en relatif (`/api/v1/...`),
 * ce qui couvre la production (Caddy sert le front et proxifie /api) et le
 * developpement (proxy Vite vers le backend, voir vite.config.ts). Une URL
 * absolue n'est necessaire que pour un front heberge ailleurs que l'API.
 */
const API_BASE_URL = ((import.meta.env.VITE_API_URL as string | undefined) ?? '').replace(/\/$/, '')

/** Delai au-dela duquel une requete est abandonnee (reseau mobile degrade compris). */
const REQUEST_TIMEOUT_MS = 20_000

/*
 * Cles de l'ancien client (jetons dans localStorage, constats F355/F405 de l'audit).
 * Elles ne sont plus jamais ecrites : le refresh token vit dans un cookie HttpOnly
 * pose par l'API, hors de portee de tout script. Un reste de l'ancien client est
 * utilise une seule fois (corps de /auth/refresh, periode de transition) puis efface.
 */
const LEGACY_ACCESS_TOKEN_KEY = 'ekuiseo.accessToken'
const LEGACY_REFRESH_TOKEN_KEY = 'ekuiseo.refreshToken'

/* Routes d'authentification : cookie de session et en-tete anti-CSRF. */
const AUTH_PATH_PREFIX = '/api/v1/auth/'
const REFRESH_PATH = '/api/v1/auth/refresh'
/*
 * En-tete exige par l'API des que le refresh token vient du cookie (403 sinon) :
 * un navigateur ne l'ajoute jamais a une navigation ou a un formulaire cross-site,
 * ce qui complete SameSite=Strict contre le CSRF.
 */
const CLIENT_HEADER = 'X-Requested-With'
const CLIENT_HEADER_VALUE = 'XMLHttpRequest'

/* ------------------------------------------------------------ Session */

/**
 * Cause d'un changement d'etat de session : `login` (code verifie, ou jeton
 * renouvele sur 401), `restored` (fin de la restauration au chargement, reussie
 * ou non : `authenticated` dit laquelle), `logout`, `expired` (jeton refuse par
 * le serveur au milieu d'une requete).
 */
export type AuthChangeReason = 'login' | 'restored' | 'logout' | 'expired'
type AuthListener = (authenticated: boolean, reason: AuthChangeReason) => void

/*
 * Le jeton d'acces (60 min) vit uniquement en memoire : il disparait avec l'onglet
 * et n'est lisible par aucun script tiers via le stockage. La session survit au
 * rechargement grace au cookie HttpOnly `ekuiseo_refresh` (SameSite=Strict,
 * Path=/api/v1/auth) que seul le serveur lit : au demarrage, `restoreSession`
 * appelle POST /auth/refresh pour rouvrir la session sans rien stocker cote client.
 * Tout changement est notifie pour que React (garde de route, en-tete) reagisse
 * immediatement, y compris a une expiration detectee au milieu d'une requete.
 */
let memoryAccess: string | null = null
/** Vrai entre le debut de `restoreSession` et sa fin : la garde de route attend au lieu de rediriger. */
let restoring = false
const listeners = new Set<AuthListener>()

function readLegacy(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function clearLegacyStorage() {
  try {
    localStorage.removeItem(LEGACY_ACCESS_TOKEN_KEY)
    localStorage.removeItem(LEGACY_REFRESH_TOKEN_KEY)
  } catch {
    /* stockage indisponible : rien a effacer */
  }
}

function notify(reason: AuthChangeReason) {
  const authenticated = !!memoryAccess
  for (const listener of listeners) listener(authenticated, reason)
}

export const authStore = {
  getAccessToken(): string | null {
    return memoryAccess
  },
  isAuthenticated(): boolean {
    return !!memoryAccess
  },
  /** Session en cours de restauration au chargement (cookie -> jeton d'acces) : ne pas conclure a l'absence de session. */
  isRestoring(): boolean {
    return restoring
  },
  setAccessToken(accessToken: string, reason: AuthChangeReason = 'login') {
    memoryAccess = accessToken
    restoring = false
    notify(reason)
  },
  /** Fin de session locale : jeton en memoire et eventuels restes de l'ancien client dans localStorage. */
  clear(reason: AuthChangeReason = 'logout') {
    const wasAuthenticated = !!memoryAccess
    memoryAccess = null
    clearLegacyStorage()
    if (wasAuthenticated) notify(reason)
  },
  subscribe(listener: AuthListener): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
}

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
  /** Type attendu en reponse (application/json par defaut ; text/csv pour les exports). */
  accept?: string
}

/*
 * Issue d'un POST /auth/refresh : la session est rouverte (`AuthResponse`), refusee par
 * le serveur (`rejected` : cookie absent, jeton revoque ou expire), ou injoignable
 * (`unreachable` : hors ligne, delai, panne) - auquel cas on ne conclut rien.
 */
type RefreshOutcome = { kind: 'ok'; data: AuthResponse } | { kind: 'rejected' } | { kind: 'unreachable' }

let refreshInFlight: Promise<RefreshOutcome> | null = null

/**
 * Un seul POST /auth/refresh a la fois, partage entre la restauration au chargement
 * et les 401 concurrents. Le navigateur joint le cookie HttpOnly (`credentials`) ;
 * l'en-tete anti-CSRF est obligatoire des que le jeton vient du cookie. Transition :
 * un refresh token laisse dans localStorage par l'ancien client part une derniere
 * fois dans le corps, puis les deux anciennes cles sont effacees quelle que soit la
 * reponse (le serveur pose le cookie s'il l'a accepte, il est mort sinon).
 * Le jeton recu est pose en memoire ici meme, une seule fois pour tous les appelants.
 */
function refreshOnce(reason: AuthChangeReason): Promise<RefreshOutcome> {
  if (!refreshInFlight) {
    const legacyRefreshToken = readLegacy(LEGACY_REFRESH_TOKEN_KEY)
    const headers: Record<string, string> = { Accept: 'application/json', [CLIENT_HEADER]: CLIENT_HEADER_VALUE }
    if (legacyRefreshToken) headers['Content-Type'] = 'application/json'
    refreshInFlight = fetch(`${API_BASE_URL}${REFRESH_PATH}`, {
      method: 'POST',
      headers,
      credentials: 'include',
      body: legacyRefreshToken ? JSON.stringify({ refreshToken: legacyRefreshToken }) : undefined,
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    })
      .then(async (res): Promise<RefreshOutcome> => {
        clearLegacyStorage()
        if (!res.ok) {
          // Jeton refuse par le serveur : la session est bel et bien terminee (au
          // chargement, rien n'etait ouvert : clear ne notifie alors personne).
          authStore.clear('expired')
          return { kind: 'rejected' }
        }
        const data = (await res.json()) as AuthResponse
        authStore.setAccessToken(data.accessToken, reason)
        return { kind: 'ok', data }
      })
      .catch((): RefreshOutcome => ({ kind: 'unreachable' }))
      .finally(() => {
        refreshInFlight = null
      })
  }
  return refreshInFlight
}

/** Renouvellement sur 401 : nouveau jeton d'acces, ou null (session terminee ou serveur injoignable). */
async function refreshAccessToken(): Promise<string | null> {
  const outcome = await refreshOnce('login')
  return outcome.kind === 'ok' ? outcome.data.accessToken : null
}

/**
 * Restauration de la session au chargement de la page : sans jeton en memoire, un
 * POST /auth/refresh tente de rouvrir la session depuis le cookie HttpOnly. Silencieux :
 * un refus (401 : pas de cookie, jeton expire ou revoque) signifie simplement que
 * l'utilisateur n'est pas connecte. Pendant l'appel, `authStore.isRestoring()` est vrai
 * et la garde de route attend au lieu de renvoyer vers la connexion. Serveur
 * injoignable (PWA lancee hors ligne) : nouvelle tentative au retour du reseau.
 * Renvoie la session rouverte (profil compris) ou null.
 */
export async function restoreSession(): Promise<AuthResponse | null> {
  if (memoryAccess) return null
  restoring = true
  const outcome = await refreshOnce('restored')
  if (outcome.kind === 'ok') return outcome.data
  restoring = false
  if (outcome.kind === 'unreachable' && typeof window !== 'undefined') {
    window.addEventListener('online', () => void restoreSession(), { once: true })
  }
  // Fin de restauration sans session : la garde de route peut maintenant rediriger.
  notify('restored')
  return null
}

/**
 * Compose le signal de l'appelant (TanStack Query : `cancelQueries`, demontage
 * d'un ecran) avec le delai maximal. Sans `AbortSignal.any` (navigateurs anciens),
 * la composition est faite a la main : le signal amont ET le delai annulent la
 * requete (audit F244 : le delai seul laissait courir les requetes abandonnees).
 */
function withTimeout(signal: AbortSignal | undefined, timeoutMs: number): AbortSignal {
  const timeout = AbortSignal.timeout(timeoutMs)
  if (!signal) return timeout
  if (typeof AbortSignal.any === 'function') return AbortSignal.any([signal, timeout])
  const controller = new AbortController()
  const abort = (source: AbortSignal) => () => controller.abort(source.reason)
  if (signal.aborted) controller.abort(signal.reason)
  else signal.addEventListener('abort', abort(signal), { once: true })
  timeout.addEventListener('abort', abort(timeout), { once: true })
  return controller.signal
}

/* ------------------------------------------------------------- Suspension */

type SuspensionListener = (problem: ProblemDetail) => void
const suspensionListeners = new Set<SuspensionListener>()

/**
 * Compte suspendu (403 RFC 7807 de type « account-suspended », audit F257) :
 * l'ecran dedie s'affiche au lieu d'une redirection vers la connexion. Le client
 * HTTP le signale une seule fois par reponse ; AppShell s'y abonne.
 */
export const suspensionStore = {
  subscribe(listener: SuspensionListener): () => void {
    suspensionListeners.add(listener)
    return () => suspensionListeners.delete(listener)
  },
}

function notifySuspension(problem: ProblemDetail) {
  for (const listener of suspensionListeners) listener(problem)
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

/**
 * Appel brut avec JWT et rafraichissement automatique en cas de 401 ; ne lit pas le corps.
 * Les routes /api/v1/auth/* partent avec le cookie de session (`credentials: 'include'`,
 * necessaire pour que le navigateur enregistre celui pose par /otp/verify et l'envoie a
 * /refresh et /logout) et l'en-tete anti-CSRF ; les autres restent en `same-origin`,
 * sans cookie utile (Path=/api/v1/auth) : elles n'ont que l'en-tete Authorization.
 */
async function authorizedFetch(path: string, options: RequestOptions = {}): Promise<Response> {
  const { method = 'GET', body, auth = true, signal, timeoutMs = REQUEST_TIMEOUT_MS, accept = 'application/json' } = options
  const authRoute = path.startsWith(AUTH_PATH_PREFIX)

  const doFetch = async (token: string | null): Promise<Response> => {
    const headers: Record<string, string> = { Accept: accept }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (auth && token) headers['Authorization'] = `Bearer ${token}`
    if (authRoute) headers[CLIENT_HEADER] = CLIENT_HEADER_VALUE
    try {
      return await fetch(`${API_BASE_URL}${path}`, {
        method,
        headers,
        credentials: authRoute ? 'include' : 'same-origin',
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
    // Jeton d'acces refuse : on tente le cookie de rafraichissement. Refus -> la session
    // est terminee (refreshOnce l'a videe) ; reseau -> on rend le 401 initial sans conclure.
    const newToken = await refreshAccessToken()
    if (newToken) res = await doFetch(newToken)
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
  if (res.status === 403 && problem && isSuspendedProblem(problem)) notifySuspension(problem)
  return new ApiError(res.status, problem, `Erreur HTTP ${res.status}`)
}

function isSuspendedProblem(problem: ProblemDetail): boolean {
  return [problem.type, problem.title].some((value) => typeof value === 'string' && value.includes('account-suspended'))
}

/**
 * Telecharge un fichier servi par l'API (ex. export CSV du back-office) avec le
 * meme JWT et la meme gestion du 401 que les appels JSON, puis le remet au
 * navigateur sous `filename`. Un simple lien <a href> ne pourrait pas porter
 * l'en-tete Authorization.
 */
export async function downloadFile(path: string, filename: string): Promise<void> {
  // Un export CSV est servi avec produces=text/csv : un Accept JSON donnerait 406 (constat F451).
  const accept = filename.endsWith('.csv') ? 'text/csv, application/json' : 'application/json, */*'
  const res = await authorizedFetch(path, { timeoutMs: 60_000, accept })
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
