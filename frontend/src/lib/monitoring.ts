/**
 * Remontee des erreurs frontend (audit F440).
 *
 * Chaque erreur part en POST JSON vers un collecteur : par defaut le point d'entree
 * de l'API (`POST /api/v1/client-errors`, journalise cote serveur sous le logger
 * `bj.ekuiseo.api.client`), ou l'URL donnee par `VITE_ERROR_REPORT_URL` (Sentry,
 * GlitchTip...). La valeur `off` coupe l'envoi : l'erreur reste alors dans la
 * console (tests, poste de developpement). Le rapport ne contient AUCUNE donnee personnelle :
 * message, pile tronquee, route (chemin seul, sans parametres), version du
 * paquet et agent utilisateur. Jamais de jeton, de numero ni de corps de requete.
 *
 * Sources branchees : window.onerror, unhandledrejection (main.tsx),
 * ErrorBoundary#componentDidCatch, et les caches TanStack Query pour les
 * echecs 5xx non transitoires (lib/queryClient.ts).
 */

const DEFAULT_REPORT_PATH = '/api/v1/client-errors'

/** Collecteur : URL explicite, `off` pour desactiver, sinon le point d'entree de l'API (meme base que client.ts). */
export function resolveReportUrl(env: Record<string, string | undefined> = import.meta.env as Record<string, string | undefined>): string | null {
  const configured = env.VITE_ERROR_REPORT_URL?.trim()
  if (configured === 'off' || configured === 'none' || configured === 'false') return null
  if (configured) return configured
  const apiBase = (env.VITE_API_URL ?? '').replace(/\/$/, '')
  return `${apiBase}${DEFAULT_REPORT_PATH}`
}
const APP_VERSION = (import.meta.env.VITE_APP_VERSION as string | undefined) || 'dev'
const STACK_MAX_CHARS = 2_000
/** Au-dela, on arrete d'envoyer : une boucle d'erreurs ne doit pas inonder le collecteur. */
const MAX_REPORTS_PER_SESSION = 20

export interface ErrorReport {
  message: string
  stack?: string
  source: string
  route: string
  version: string
  userAgent: string
  occurredAt: string
  componentStack?: string
  /** Statut HTTP pour une erreur d'API, pour trier vite au tableau de bord. */
  status?: number
}

let sent = 0
/** Empreintes deja envoyees dans la session : la meme erreur repetee ne part qu'une fois. */
const seen = new Set<string>()

function toMessage(error: unknown): { message: string; stack?: string; status?: number } {
  if (error instanceof Error) {
    const status = 'status' in error && typeof error.status === 'number' ? error.status : undefined
    return { message: error.message || error.name, stack: error.stack?.slice(0, STACK_MAX_CHARS), status }
  }
  if (typeof error === 'string') return { message: error }
  return { message: 'Erreur inconnue' }
}

/** Route courante sans parametres : un `?booking=` ou un identifiant dans la requete n'a rien a faire dans un rapport. */
function currentRoute(): string {
  if (typeof window === 'undefined') return ''
  return window.location.pathname
}

export function buildReport(error: unknown, context: { source: string; componentStack?: string }): ErrorReport {
  const { message, stack, status } = toMessage(error)
  return {
    message,
    stack,
    status,
    source: context.source,
    componentStack: context.componentStack?.slice(0, STACK_MAX_CHARS),
    route: currentRoute(),
    version: APP_VERSION,
    userAgent: typeof navigator === 'undefined' ? '' : navigator.userAgent,
    occurredAt: new Date().toISOString(),
  }
}

/**
 * Envoie (ou journalise) une erreur. Ne jette jamais : une panne du collecteur
 * ne doit pas ajouter une erreur a l'erreur.
 */
export function reportError(error: unknown, context: { source: string; componentStack?: string }): void {
  const report = buildReport(error, context)
  const reportUrl = resolveReportUrl()
  if (!reportUrl) {
    // Pas de collecteur configure : la console reste la seule trace (developpement, ou choix d'exploitation).
    // eslint-disable-next-line no-console
    console.error(`[${report.source}]`, error, report.componentStack ?? '')
    return
  }
  const fingerprint = `${report.source}|${report.message}|${report.route}`
  if (seen.has(fingerprint) || sent >= MAX_REPORTS_PER_SESSION) return
  seen.add(fingerprint)
  sent += 1
  try {
    const body = JSON.stringify(report)
    // sendBeacon survit a la fermeture de l'onglet ; fetch keepalive en repli.
    if (typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
      const ok = navigator.sendBeacon(reportUrl, new Blob([body], { type: 'application/json' }))
      if (ok) return
    }
    void fetch(reportUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
      keepalive: true,
    }).catch(() => undefined)
  } catch {
    /* rien : le rapport est perdu, l'application continue */
  }
}

/** Branche les deux ecouteurs globaux du navigateur. Idempotent. */
let installed = false
export function installGlobalErrorHandlers(): void {
  if (installed || typeof window === 'undefined') return
  installed = true
  window.addEventListener('error', (event) => {
    // Les erreurs de chargement de ressources (img, script) n'ont pas de `error` : on garde le message de l'evenement.
    reportError(event.error ?? event.message, { source: 'window.onerror' })
  })
  window.addEventListener('unhandledrejection', (event) => {
    reportError(event.reason, { source: 'unhandledrejection' })
  })
}

/**
 * Import paresseux casse apres un deploiement : le navigateur tient encore
 * l'ancien index et demande un chunk hache qui n'existe plus (audit F344).
 */
export function isStaleChunkError(error: Error): boolean {
  return /Failed to fetch dynamically imported module|Importing a module script failed|error loading dynamically imported module/i.test(
    error.message,
  )
}
