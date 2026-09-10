import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { LiveStreamEvent, LiveStreamState } from '@/api/types'
import {
  SseParser,
  clearStoredParticipants,
  openLiveStream,
  participantKey,
  readStoredParticipants,
  reconnectDelayMs,
  storeParticipants,
  toLiveStreamEvent,
} from './liveStream'

function streamOf(...chunks: string[]): Response {
  const encoder = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
      controller.close()
    },
  })
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

describe('SseParser', () => {
  it('decoupe les evenements, concatene les data et ignore les commentaires', () => {
    const parser = new SseParser()
    const events = parser.feed('event: position\ndata: {"a":1}\n\n: battement\n\nid: 7\ndata: l1\ndata: l2\n\n')
    expect(events).toEqual([
      { event: 'position', data: '{"a":1}', id: undefined },
      { event: 'message', data: 'l1\nl2', id: '7' },
    ])
  })

  it('garde un evenement incomplet entre deux morceaux, y compris un CRLF coupe', () => {
    const parser = new SseParser()
    expect(parser.feed('event:snap')).toEqual([])
    expect(parser.feed('shot\r')).toEqual([])
    expect(parser.feed('\ndata:{"type":"snapshot"}\r\n\r')).toEqual([])
    expect(parser.feed('\n')).toEqual([{ event: 'snapshot', data: '{"type":"snapshot"}', id: undefined }])
  })
})

describe('toLiveStreamEvent', () => {
  it('interprete les evenements du contrat et ignore les battements et le JSON illisible', () => {
    expect(toLiveStreamEvent({ event: 'ping', data: '1757491200' })).toBeNull()
    expect(toLiveStreamEvent({ event: 'position', data: '{oops' })).toBeNull()
    expect(toLiveStreamEvent({ event: 'end', data: '{"type":"end","reason":"TRIP_COMPLETED"}' })).toEqual({
      type: 'end',
      reason: 'TRIP_COMPLETED',
    })
    // Le nom SSE fait foi quand le JSON ne porte pas de type.
    expect(toLiveStreamEvent({ event: 'status', data: '{"tripStatus":"ONGOING","sharingEnabled":true,"intervalSeconds":15}' })).toMatchObject({
      type: 'status',
      tripStatus: 'ONGOING',
    })
    expect(toLiveStreamEvent({ event: 'unknown', data: '{"type":"unknown"}' })).toBeNull()
  })
})

describe('reconnexion', () => {
  it('double le delai de 1 s a 30 s au plus', () => {
    expect(reconnectDelayMs(0)).toBe(1_000)
    expect(reconnectDelayMs(1)).toBe(2_000)
    expect(reconnectDelayMs(3)).toBe(8_000)
    expect(reconnectDelayMs(5)).toBe(30_000)
    expect(reconnectDelayMs(12)).toBe(30_000)
  })
})

describe('memoire des positions', () => {
  afterEach(() => sessionStorage.clear())

  it('identifie le conducteur et chaque passager par sa reservation', () => {
    expect(participantKey({ role: 'DRIVER', bookingId: null })).toBe('driver')
    expect(participantKey({ role: 'PASSENGER', bookingId: 'b-1' })).toBe('booking:b-1')
  })

  it('ecrit, relit et efface dans sessionStorage, sans jamais lever', () => {
    const known = {
      driver: {
        role: 'DRIVER' as const, bookingId: null, firstName: 'R', lat: 6.4, lng: 2.35, heading: null, speedKmh: null,
        accuracyM: null, recordedAt: '2026-09-10T08:00:00Z', flags: [], receivedAt: 1, ageAtReceipt: 0,
      },
    }
    storeParticipants('t-1', known)
    expect(readStoredParticipants('t-1')).toEqual(known)
    expect(readStoredParticipants('t-2')).toEqual({})
    clearStoredParticipants('t-1')
    expect(readStoredParticipants('t-1')).toEqual({})
    sessionStorage.setItem('ekuiseo.live.t-3', '{broken')
    expect(readStoredParticipants('t-3')).toEqual({})
  })
})

describe('openLiveStream', () => {
  const snapshot = 'event:snapshot\ndata:{"type":"snapshot","tripStatus":"ONGOING","sharingEnabled":true,"intervalSeconds":15,"participants":[]}\n\n'
  const position = 'event:position\ndata:{"type":"position","participant":{"role":"DRIVER","bookingId":null,"firstName":"R","lat":6.4,"lng":2.35,"heading":null,"speedKmh":null,"accuracyM":null,"recordedAt":"2026-09-10T08:00:00Z","flags":[]}}\n\n'
  const end = 'event:end\ndata:{"type":"end","reason":"TRIP_COMPLETED"}\n\n'

  function harness() {
    const events: LiveStreamEvent[] = []
    const states: LiveStreamState[] = []
    const waits: number[] = []
    const wait = vi.fn(async (ms: number) => {
      waits.push(ms)
    })
    return {
      events,
      states,
      waits,
      wait,
      handlers: {
        onEvent: (event: LiveStreamEvent) => events.push(event),
        onState: (state: LiveStreamState) => states.push(state),
      },
    }
  }

  async function settle() {
    for (let i = 0; i < 20; i++) await Promise.resolve()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }

  it('lit les evenements dans l ordre et s arrete sur end sans se reconnecter', async () => {
    const h = harness()
    const fetchImpl = vi.fn(async () => streamOf(snapshot, position, end))
    openLiveStream('t-1', h.handlers, { fetchImpl, wait: h.wait })
    await settle()

    expect(h.events.map((e) => e.type)).toEqual(['snapshot', 'position', 'end'])
    expect(h.states).toEqual(['connecting', 'open', 'ended'])
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(h.wait).not.toHaveBeenCalled()
  })

  it('se reconnecte avec un delai croissant apres une coupure, puis repart de 1 s des qu un evenement est arrive', async () => {
    const h = harness()
    let calls = 0
    const fetchImpl = vi.fn(async () => {
      calls += 1
      if (calls <= 2) throw new TypeError('reseau')
      if (calls === 3) return streamOf(snapshot)
      return streamOf(end)
    })
    openLiveStream('t-1', h.handlers, { fetchImpl, wait: h.wait })
    await settle()

    // Deux echecs (1 s puis 2 s), un flux ferme apres un instantane (1 s), puis end.
    expect(h.waits).toEqual([2_000, 4_000, 1_000])
    expect(h.states).toEqual(['connecting', 'reconnecting', 'reconnecting', 'reconnecting', 'reconnecting', 'open', 'reconnecting', 'open', 'ended'])
    expect(h.events.map((e) => e.type)).toEqual(['snapshot', 'end'])
  })

  it('abandonne sur une erreur definitive (403) et sur stop()', async () => {
    const forbidden = harness()
    const fetchForbidden = vi.fn(async () => new Response('{"status":403}', { status: 403, headers: { 'Content-Type': 'application/problem+json' } }))
    openLiveStream('t-1', forbidden.handlers, { fetchImpl: fetchForbidden, wait: forbidden.wait })
    await settle()
    expect(forbidden.states).toEqual(['connecting', 'stopped'])
    expect(fetchForbidden).toHaveBeenCalledTimes(1)

    const stopped = harness()
    let signalSeen: AbortSignal | null = null
    const fetchNever = vi.fn(
      (_path: string, signal: AbortSignal) =>
        new Promise<Response>((_resolve, reject) => {
          signalSeen = signal
          signal.addEventListener('abort', () => reject(new DOMException('abandon', 'AbortError')))
        }),
    )
    const handle = openLiveStream('t-1', stopped.handlers, { fetchImpl: fetchNever, wait: stopped.wait })
    await settle()
    handle.stop()
    await settle()
    expect(signalSeen!.aborted).toBe(true)
    expect(stopped.states).toEqual(['connecting', 'stopped'])
    expect(new ApiError(403, null, 'x').status).toBe(403)
  })
})
