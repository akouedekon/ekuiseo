import { ApiError, authorizedFetch, toApiError } from '@/api/client'
import type { LiveParticipant, LiveStreamEvent } from '@/api/types'

/*
 * Client du flux SSE du suivi en direct (GET /api/v1/trips/{id}/live/stream, V28).
 *
 * Pas d EventSource : il ne sait pas porter l en-tete Authorization et un jeton dans l URL
 * finirait dans les journaux des proxys. On lit donc la reponse `fetch` en flux
 * (ReadableStream), on decoupe les evenements SSE a la main (`SseParser`), et on se
 * reconnecte seul avec un delai croissant (1 s -> 30 s) tant que le serveur n a pas envoye
 * `end`. La derniere position connue de chaque participant est gardee en memoire et dans
 * sessionStorage : au retour sur la page, elle s affiche aussitot, avec son age reel.
 */

export type LiveStreamState = 'connecting' | 'open' | 'reconnecting' | 'ended' | 'stopped'

export interface ParsedSseEvent {
  event: string
  data: string
  id?: string
}

/** Delais de reconnexion : 1, 2, 4, 8, 16, puis 30 s, pas plus. */
export const RECONNECT_MIN_MS = 1_000
export const RECONNECT_MAX_MS = 30_000

export function reconnectDelayMs(attempt: number): number {
  if (attempt <= 0) return RECONNECT_MIN_MS
  return Math.min(RECONNECT_MAX_MS, RECONNECT_MIN_MS * 2 ** attempt)
}

/**
 * Decoupe un flux texte en evenements SSE (RFC « Server-Sent Events ») : un evenement se
 * termine par une ligne vide ; `event:` nomme l evenement (`message` par defaut), les lignes
 * `data:` se concatenent avec un saut de ligne, `:` ouvre un commentaire, `id:` est conserve.
 * Le parseur garde ce qui n est pas encore termine entre deux `feed`, y compris un `\r\n`
 * coupe en deux.
 */
export class SseParser {
  private buffer = ''
  private event = ''
  private data: string[] = []
  private id: string | undefined

  feed(chunk: string): ParsedSseEvent[] {
    this.buffer += chunk
    const out: ParsedSseEvent[] = []
    for (;;) {
      const newline = this.buffer.search(/\r\n|\r|\n/)
      if (newline === -1) break
      // Un `\r` en fin de tampon peut etre suivi d un `\n` dans le prochain morceau : on attend.
      if (this.buffer[newline] === '\r' && newline === this.buffer.length - 1) break
      const line = this.buffer.slice(0, newline)
      const separatorLength = this.buffer.startsWith('\r\n', newline) ? 2 : 1
      this.buffer = this.buffer.slice(newline + separatorLength)
      const event = this.line(line)
      if (event) out.push(event)
    }
    return out
  }

  private line(line: string): ParsedSseEvent | null {
    if (line === '') {
      if (this.data.length === 0 && this.event === '') return null
      const event: ParsedSseEvent = { event: this.event || 'message', data: this.data.join('\n'), id: this.id }
      this.event = ''
      this.data = []
      return event
    }
    if (line.startsWith(':')) return null
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') this.event = value
    else if (field === 'data') this.data.push(value)
    else if (field === 'id') this.id = value
    return null
  }
}

/** Interprete un evenement SSE du serveur ; null pour un battement ou un JSON illisible. */
export function toLiveStreamEvent(parsed: ParsedSseEvent): LiveStreamEvent | null {
  if (parsed.event === 'ping' || parsed.event === 'message') return null
  try {
    const value = JSON.parse(parsed.data) as { type?: string }
    if (!value || typeof value !== 'object') return null
    const type = value.type ?? parsed.event
    if (type !== 'snapshot' && type !== 'position' && type !== 'status' && type !== 'end') return null
    return { ...value, type } as LiveStreamEvent
  } catch {
    return null
  }
}

/* ------------------------------------------------ memoire des dernieres positions */

/** Position connue d un participant, avec l instant local de reception (jamais l horloge du serveur seule). */
export interface KnownParticipant extends LiveParticipant {
  /** `Date.now()` a la reception de l evenement ou de l instantane. */
  receivedAt: number
  /** Age de la position (s) tel que le serveur l a mesure au moment de l envoi ; 0 pour un evenement du flux. */
  ageAtReceipt: number
}

/** Cle d un participant dans la memoire : le conducteur est unique, un passager est identifie par sa reservation. */
export function participantKey(participant: Pick<LiveParticipant, 'role' | 'bookingId'>): string {
  return participant.role === 'DRIVER' ? 'driver' : `booking:${participant.bookingId ?? '?'}`
}

const STORAGE_PREFIX = 'ekuiseo.live.'

export function readStoredParticipants(tripId: string): Record<string, KnownParticipant> {
  try {
    const raw = sessionStorage.getItem(STORAGE_PREFIX + tripId)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as Record<string, KnownParticipant>
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

export function storeParticipants(tripId: string, participants: Record<string, KnownParticipant>): void {
  try {
    sessionStorage.setItem(STORAGE_PREFIX + tripId, JSON.stringify(participants))
  } catch {
    /* stockage indisponible ou plein : la memoire vive suffit */
  }
}

export function clearStoredParticipants(tripId: string): void {
  try {
    sessionStorage.removeItem(STORAGE_PREFIX + tripId)
  } catch {
    /* rien a effacer */
  }
}

/* --------------------------------------------------------------- connexion */

export interface LiveStreamHandlers {
  onEvent: (event: LiveStreamEvent) => void
  onState: (state: LiveStreamState, detail: { attempt: number; error?: unknown }) => void
}

export interface LiveStreamOptions {
  /** Remplace `authorizedFetch` dans les tests. */
  fetchImpl?: (path: string, signal: AbortSignal) => Promise<Response>
  /** Remplace `setTimeout` (delai de reconnexion) dans les tests. */
  wait?: (ms: number, signal: AbortSignal) => Promise<void>
}

export interface LiveStreamHandle {
  /** Ferme la connexion et empeche toute reconnexion. */
  stop: () => void
}

function defaultFetch(path: string, signal: AbortSignal): Promise<Response> {
  return authorizedFetch(path, { accept: 'text/event-stream', timeoutMs: 0, signal })
}

function defaultWait(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) {
      resolve()
      return
    }
    const id = setTimeout(done, ms)
    function done() {
      signal.removeEventListener('abort', done)
      clearTimeout(id)
      resolve()
    }
    signal.addEventListener('abort', done, { once: true })
  })
}

/** Un 401/403/404 ne se corrige pas en reessayant : on s arrete. Tout le reste (reseau, 5xx, 429) se retente. */
function isDefinitive(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 401 || error.status === 403 || error.status === 404)
}

/**
 * Ouvre le flux d un trajet et le maintient ouvert. `onEvent` recoit les evenements
 * interpretes (snapshot, position, status, end) ; `onState` suit la connexion. Apres `end`
 * le flux ne se reconnecte plus ; apres une erreur definitive (401/403/404) non plus.
 */
export function openLiveStream(tripId: string, handlers: LiveStreamHandlers, options: LiveStreamOptions = {}): LiveStreamHandle {
  const controller = new AbortController()
  const fetchImpl = options.fetchImpl ?? defaultFetch
  const wait = options.wait ?? defaultWait
  const path = `/api/v1/trips/${tripId}/live/stream`
  let attempt = 0
  let connections = 0

  const run = async () => {
    while (!controller.signal.aborted) {
      handlers.onState(connections === 0 ? 'connecting' : 'reconnecting', { attempt })
      connections += 1
      let ended = false
      let receivedAny = false
      try {
        const response = await fetchImpl(path, controller.signal)
        if (!response.ok) throw await toApiError(response)
        if (!response.body) throw new Error('Flux vide')
        handlers.onState('open', { attempt })
        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        const parser = new SseParser()
        for (;;) {
          const { value, done } = await reader.read()
          if (done) break
          for (const parsed of parser.feed(decoder.decode(value, { stream: true }))) {
            const event = toLiveStreamEvent(parsed)
            if (!event) continue
            receivedAny = true
            handlers.onEvent(event)
            if (event.type === 'end') {
              ended = true
              break
            }
          }
          if (ended) break
        }
        if (!ended) {
          try {
            await reader.cancel()
          } catch {
            /* deja ferme */
          }
        }
      } catch (error) {
        if (controller.signal.aborted) break
        if (isDefinitive(error)) {
          handlers.onState('stopped', { attempt, error })
          return
        }
        handlers.onState('reconnecting', { attempt, error })
      }
      if (controller.signal.aborted) break
      if (ended) {
        handlers.onState('ended', { attempt })
        return
      }
      // Un flux qui a livre des evenements puis s est ferme proprement (delai serveur) repart vite.
      attempt = receivedAny ? 0 : attempt + 1
      await wait(reconnectDelayMs(attempt), controller.signal)
    }
    handlers.onState('stopped', { attempt })
  }

  void run()
  return {
    stop: () => controller.abort(),
  }
}
