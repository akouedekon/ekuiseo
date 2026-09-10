import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { LiveParticipant, LivePositionResponse } from '@/api/types'
import { createTestQueryClient, installFakeApi } from '@/test/api'
import { reduce, useLiveStream } from './useLiveStream'

const DRIVER: LiveParticipant = {
  role: 'DRIVER', bookingId: null, firstName: 'Marcellin', lat: 6.40, lng: 2.35, heading: 310, speedKmh: 50,
  accuracyM: 8, flags: [], recordedAt: '2026-09-10T07:59:48Z',
}
const ME: LiveParticipant = { ...DRIVER, role: 'PASSENGER', bookingId: 'b-1', firstName: 'Awa', lat: 6.38, lng: 2.40 }

function snapshot(overrides: Partial<LivePositionResponse> = {}): LivePositionResponse {
  return {
    enabled: true,
    position: { lat: DRIVER.lat, lng: DRIVER.lng, heading: 310, speedKmh: 50, accuracyM: 8, recordedAt: DRIVER.recordedAt },
    staleSeconds: 12,
    tripStatus: 'ONGOING',
    departureAt: '2026-09-10T07:30:00Z',
    shareToken: 'tok',
    intervalSeconds: 15,
    participants: [DRIVER],
    serverTime: '2026-09-10T08:00:00Z',
    ...overrides,
  }
}

function sse(events: string[]): Response {
  const encoder = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const event of events) controller.enqueue(encoder.encode(event))
      controller.close()
    },
  })
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

describe('reduce', () => {
  const base = {
    participants: {},
    connection: 'idle' as const,
    attempt: 0,
    sharingEnabled: null,
    tripStatus: null,
    intervalSeconds: 30,
    endedReason: null,
    clockOffsetMs: null,
  }

  it('date les participants de l instantane avec l heure serveur et estime le decalage', () => {
    const receivedAt = Date.parse('2026-09-10T08:00:05Z') // l appareil est 5 s en avance
    const next = reduce(base, { type: 'snapshot', data: snapshot(), receivedAt })
    expect(next.participants.driver).toMatchObject({ firstName: 'Marcellin', receivedAt, ageAtReceipt: 12 })
    expect(next.clockOffsetMs).toBe(5_000)
    expect(next.sharingEnabled).toBe(true)
    expect(next.intervalSeconds).toBe(15)
  })

  it('met a jour une position, retire le conducteur quand le partage s arrete, termine le trajet sur end', () => {
    const at = 1_000
    let state = reduce(base, { type: 'event', event: { type: 'snapshot', tripStatus: 'ONGOING', sharingEnabled: true, intervalSeconds: 15, participants: [DRIVER, ME] }, receivedAt: at })
    expect(Object.keys(state.participants).sort()).toEqual(['booking:b-1', 'driver'])
    state = reduce(state, { type: 'event', event: { type: 'position', participant: { ...DRIVER, lat: 6.41 } }, receivedAt: at + 5_000 })
    expect(state.participants.driver).toMatchObject({ lat: 6.41, receivedAt: at + 5_000, ageAtReceipt: 0 })
    state = reduce(state, { type: 'event', event: { type: 'status', tripStatus: 'ONGOING', sharingEnabled: false, intervalSeconds: 15 }, receivedAt: at + 6_000 })
    expect(state.participants.driver).toBeUndefined()
    expect(state.participants['booking:b-1']).toBeDefined()
    expect(state.sharingEnabled).toBe(false)
    state = reduce(state, { type: 'event', event: { type: 'end', reason: 'TRIP_COMPLETED' }, receivedAt: at + 7_000 })
    expect(state.endedReason).toBe('TRIP_COMPLETED')
    expect(state.tripStatus).toBe('COMPLETED')
    // Le partage reactive ne rouvre pas un trajet termine.
    expect(reduce(state, { type: 'reopen' }).endedReason).toBe('TRIP_COMPLETED')
    expect(reduce({ ...state, endedReason: 'SHARING_DISABLED' }, { type: 'reopen' }).endedReason).toBeNull()
  })
})

describe('useLiveStream', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  function setup(streamResponses: () => Response | Promise<Response>, snapshotBody: (call: number) => LivePositionResponse = () => snapshot()) {
    const client = createTestQueryClient()
    let snapshotCalls = 0
    const api = installFakeApi({
      '/api/v1/trips/t-1/live': () => {
        snapshotCalls += 1
        return { body: snapshotBody(snapshotCalls) }
      },
    })
    const fetchImpl = vi.fn(async () => streamResponses())
    // Un vrai delai (court) : un flux vide qui se reconnecte en boucle doit rendre la main aux minuteries du test.
    const wait = vi.fn((_ms: number, signal: AbortSignal) => new Promise<void>((resolve) => {
      const id = setTimeout(resolve, 5)
      signal.addEventListener('abort', () => {
        clearTimeout(id)
        resolve()
      }, { once: true })
    }))
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
    return { api, fetchImpl, wait, wrapper }
  }

  it('sert l instantane REST, ouvre le flux et suit les positions, puis retombe sur l instantane apres end', async () => {
    const position = 'event:position\ndata:' + JSON.stringify({ type: 'position', participant: { ...DRIVER, lat: 6.42 } }) + '\n\n'
    const end = 'event:end\ndata:{"type":"end","reason":"SHARING_DISABLED"}\n\n'
    // Apres la coupure du partage, l instantane relu dit que le partage est arrete : le flux ne rouvre pas.
    const { api, fetchImpl, wait, wrapper } = setup(
      () => sse([position, end]),
      (call) => (call === 1 ? snapshot() : snapshot({ enabled: false, participants: [], position: null, staleSeconds: null, shareToken: null })),
    )

    const { result } = renderHook(
      () => useLiveStream('t-1', { isDriver: false, myBookingId: 'b-1', streamOptions: { fetchImpl, wait } }),
      { wrapper },
    )

    // Le flux livre une position puis la fin du partage, qui retire le conducteur (le tout en quelques ms).
    await waitFor(() => expect(result.current.endedReason).toBe('SHARING_DISABLED'))
    expect(result.current.intervalSeconds).toBe(15)
    expect(result.current.driver).toBeNull()
    expect(result.current.sharingEnabled).toBe(false)
    // L instantane a ete relu apres end, et le flux n a pas rouvert (partage toujours coupe).
    await waitFor(() => expect(api.calls().filter((c) => c.url.endsWith('/live')).length).toBeGreaterThanOrEqual(2))
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(result.current.driver).toBeNull()
  })

  it('memorise la derniere position connue pour la session', async () => {
    const { fetchImpl, wait, wrapper } = setup(() => new Promise<Response>(() => undefined))
    const { result } = renderHook(
      () => useLiveStream('t-1', { isDriver: false, myBookingId: 'b-1', streamOptions: { fetchImpl, wait } }),
      { wrapper },
    )
    await waitFor(() => expect(result.current.driver).not.toBeNull())
    const stored = JSON.parse(sessionStorage.getItem('ekuiseo.live.t-1') ?? '{}') as Record<string, { firstName: string }>
    expect(stored.driver?.firstName).toBe('Marcellin')
  })

  it('calcule l age d une position avec le decalage d horloge et n ouvre pas le flux pour un passager sans partage', async () => {
    // Le flux du conducteur reste ouvert sans rien livrer (pas de reconnexion en boucle dans le test).
    const { fetchImpl, wait, wrapper } = setup(
      () => new Promise<Response>(() => undefined),
      () => snapshot({ enabled: false, participants: [], position: null, staleSeconds: null }),
    )
    const { result } = renderHook(
      () => useLiveStream('t-1', { isDriver: false, myBookingId: 'b-1', streamOptions: { fetchImpl, wait } }),
      { wrapper },
    )
    await waitFor(() => expect(result.current.snapshot.isSuccess).toBe(true))
    expect(result.current.sharingEnabled).toBe(false)
    expect(result.current.driver).toBeNull()
    expect(fetchImpl).not.toHaveBeenCalled()

    // Le conducteur, lui, ouvre le flux meme sans partager : il attend ses passagers.
    const driverView = renderHook(
      () => useLiveStream('t-1', { isDriver: true, streamOptions: { fetchImpl, wait } }),
      { wrapper },
    )
    await waitFor(() => expect(fetchImpl).toHaveBeenCalled())
    await act(async () => driverView.unmount())

    // Le decalage d horloge vient de serverTime (08:00:00Z) : une mesure a 07:59:48Z a 12 s, quelle que soit l heure locale.
    const now = Date.now()
    const age = result.current.ageOf({ ...DRIVER, recordedAt: '2026-09-10T07:59:48Z', receivedAt: now, ageAtReceipt: 0 }, now)
    expect(age).toBeGreaterThanOrEqual(12)
    expect(age).toBeLessThanOrEqual(15)
  })
})
