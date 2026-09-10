import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useReducer, useRef } from 'react'
import type { LiveParticipant, LivePositionResponse, LiveStreamEvent, TripStatus } from '@/api/types'
import { useTripLive } from '@/hooks/useTrips'
import {
  clearStoredParticipants,
  openLiveStream,
  participantKey,
  readStoredParticipants,
  storeParticipants,
  type KnownParticipant,
  type LiveStreamOptions,
  type LiveStreamState,
} from '@/lib/liveStream'
import { clockOffsetMs, DEFAULT_INTERVAL_SECONDS, participantAgeSeconds } from '@/lib/liveTracking'

/*
 * Suivi en direct d un trajet cote lecteur (V28) : l instantane REST (TanStack Query) donne
 * l etat initial et sert de repli quand le flux est ferme ; le flux SSE (lib/liveStream)
 * apporte les positions au fil de l eau. La derniere position connue de chaque participant
 * survit a un rechargement (sessionStorage) et n est jamais presentee comme actuelle : son
 * age reel est calcule a l affichage (`ageOf`).
 */

export type EndReason = Extract<LiveStreamEvent, { type: 'end' }>['reason']

interface State {
  participants: Record<string, KnownParticipant>
  connection: LiveStreamState | 'idle'
  attempt: number
  sharingEnabled: boolean | null
  tripStatus: TripStatus | null
  intervalSeconds: number
  endedReason: EndReason | null
  /** local = serveur + offset ; null tant qu aucune reponse horodatee n est arrivee. */
  clockOffsetMs: number | null
}

type Action =
  | { type: 'snapshot'; data: LivePositionResponse; receivedAt: number }
  | { type: 'event'; event: LiveStreamEvent; receivedAt: number }
  | { type: 'connection'; state: LiveStreamState; attempt: number }
  | { type: 'reopen' }
  | { type: 'reset'; participants: Record<string, KnownParticipant> }

function known(participant: LiveParticipant, receivedAt: number, ageAtReceipt: number): KnownParticipant {
  return { ...participant, receivedAt, ageAtReceipt }
}

function withParticipant(map: Record<string, KnownParticipant>, participant: KnownParticipant): Record<string, KnownParticipant> {
  return { ...map, [participantKey(participant)]: participant }
}

function withoutDriver(map: Record<string, KnownParticipant>): Record<string, KnownParticipant> {
  if (!map.driver) return map
  const { driver: _driver, ...rest } = map
  return rest
}

export function reduce(state: State, action: Action): State {
  switch (action.type) {
    case 'reset':
      return { ...state, participants: action.participants, endedReason: null, connection: 'idle', attempt: 0 }
    case 'connection':
      return { ...state, connection: action.state, attempt: action.attempt }
    case 'reopen':
      return state.endedReason === 'SHARING_DISABLED' ? { ...state, endedReason: null } : state
    case 'snapshot': {
      const { data, receivedAt } = action
      const server = new Date(data.serverTime).getTime()
      let participants = state.participants
      for (const participant of data.participants) {
        const recorded = new Date(participant.recordedAt).getTime()
        const age = Number.isFinite(server) && Number.isFinite(recorded) ? Math.max(0, Math.floor((server - recorded) / 1000)) : 0
        participants = withParticipant(participants, known(participant, receivedAt, age))
      }
      if (!data.enabled) participants = withoutDriver(participants)
      return {
        ...state,
        participants,
        sharingEnabled: data.enabled,
        tripStatus: data.tripStatus,
        intervalSeconds: data.intervalSeconds || state.intervalSeconds,
        clockOffsetMs: Number.isFinite(server) ? clockOffsetMs(data.serverTime, receivedAt) : state.clockOffsetMs,
      }
    }
    case 'event': {
      const { event, receivedAt } = action
      switch (event.type) {
        case 'snapshot': {
          let participants = state.participants
          for (const participant of event.participants) participants = withParticipant(participants, known(participant, receivedAt, 0))
          if (!event.sharingEnabled) participants = withoutDriver(participants)
          return {
            ...state,
            participants,
            sharingEnabled: event.sharingEnabled,
            tripStatus: event.tripStatus,
            intervalSeconds: event.intervalSeconds || state.intervalSeconds,
            clockOffsetMs: event.serverTime ? clockOffsetMs(event.serverTime, receivedAt) : state.clockOffsetMs,
            endedReason: null,
          }
        }
        case 'position':
          return { ...state, participants: withParticipant(state.participants, known(event.participant, receivedAt, 0)) }
        case 'status':
          return {
            ...state,
            sharingEnabled: event.sharingEnabled,
            tripStatus: event.tripStatus,
            intervalSeconds: event.intervalSeconds || state.intervalSeconds,
            participants: event.sharingEnabled ? state.participants : withoutDriver(state.participants),
          }
        case 'end':
          return {
            ...state,
            endedReason: event.reason,
            sharingEnabled: event.reason === 'SHARING_DISABLED' ? false : state.sharingEnabled,
            tripStatus: event.reason === 'TRIP_COMPLETED' ? 'COMPLETED' : event.reason === 'TRIP_CANCELLED' ? 'CANCELLED' : state.tripStatus,
            participants: event.reason === 'SHARING_DISABLED' ? withoutDriver(state.participants) : state.participants,
          }
        default:
          return state
      }
    }
    default:
      return state
  }
}

function initialState(tripId: string | undefined): State {
  return {
    participants: tripId ? readStoredParticipants(tripId) : {},
    connection: 'idle',
    attempt: 0,
    sharingEnabled: null,
    tripStatus: null,
    intervalSeconds: DEFAULT_INTERVAL_SECONDS,
    endedReason: null,
    clockOffsetMs: null,
  }
}

export interface LiveView {
  /** Instantane REST (chargement, erreur, reessai). */
  snapshot: ReturnType<typeof useTripLive>
  participants: Record<string, KnownParticipant>
  driver: KnownParticipant | null
  /** Ma propre position telle que le serveur la connait (passager) ; null pour le conducteur. */
  me: KnownParticipant | null
  /** Passagers qui partagent (vus du conducteur ; un passager ne voit que lui-meme). */
  passengers: KnownParticipant[]
  connection: LiveStreamState | 'idle'
  /** Nombre de tentatives de reconnexion en cours (0 = connexion nominale). */
  attempt: number
  streaming: boolean
  sharingEnabled: boolean
  tripStatus: TripStatus | null
  intervalSeconds: number
  endedReason: EndReason | null
  /** Age (s) d une position a l instant `now`, avec le decalage d horloge estime. */
  ageOf: (participant: KnownParticipant, now: number) => number
  refetch: () => void
}

export interface UseLiveStreamOptions {
  enabled?: boolean
  /** Le conducteur ouvre le flux meme sans partager : il voit ses passagers. */
  isDriver?: boolean
  /** Reservation du passager, pour reconnaitre sa propre position parmi les participants. */
  myBookingId?: string | null
  /** Tests : remplace le client SSE. */
  streamOptions?: LiveStreamOptions
}

export function useLiveStream(tripId: string | undefined, options: UseLiveStreamOptions = {}): LiveView {
  const enabled = (options.enabled ?? true) && !!tripId
  const isDriver = options.isDriver ?? false
  const queryClient = useQueryClient()
  const [state, dispatch] = useReducer(reduce, tripId, initialState)
  const streaming = state.connection === 'open'
  // Sans flux, l instantane est interroge : 10 s quand le partage est actif, 30 s sinon (attente de l activation).
  const snapshot = useTripLive(tripId, { enabled, live: !streaming })
  const tripRef = useRef(tripId)

  // Changement de trajet : on repart des positions memorisees pour celui-ci.
  useEffect(() => {
    if (tripRef.current === tripId) return
    tripRef.current = tripId
    dispatch({ type: 'reset', participants: tripId ? readStoredParticipants(tripId) : {} })
  }, [tripId])

  // Instantane REST : chaque reponse alimente les positions et le decalage d horloge.
  const snapshotData = snapshot.data
  const snapshotAt = snapshot.dataUpdatedAt
  useEffect(() => {
    if (!snapshotData) return
    dispatch({ type: 'snapshot', data: snapshotData, receivedAt: snapshotAt })
  }, [snapshotData, snapshotAt])

  // Le partage reactive apres une coupure : le flux peut rouvrir.
  const sharingFromSnapshot = snapshotData?.enabled ?? null
  useEffect(() => {
    if (sharingFromSnapshot) dispatch({ type: 'reopen' })
  }, [sharingFromSnapshot])

  const sharingKnown = state.sharingEnabled ?? sharingFromSnapshot ?? false
  const terminal = state.tripStatus === 'COMPLETED' || state.tripStatus === 'CANCELLED'
  const wantStream = enabled && !terminal && state.endedReason === null && (sharingKnown || isDriver)
  // Lu dans l effet sans en etre une dependance : un objet recree a chaque rendu ne doit pas rouvrir le flux.
  const streamOptionsRef = useRef(options.streamOptions)
  useEffect(() => {
    streamOptionsRef.current = options.streamOptions
  }, [options.streamOptions])

  useEffect(() => {
    if (!wantStream || !tripId) return
    const streamOptions = streamOptionsRef.current
    const handle = openLiveStream(
      tripId,
      {
        onEvent: (event) => {
          dispatch({ type: 'event', event, receivedAt: Date.now() })
          if (event.type === 'end') {
            // L instantane REST reprend la main ; il dira si le partage est reactive.
            void queryClient.invalidateQueries({ queryKey: ['trips', tripId, 'live'] })
          }
        },
        onState: (connection, detail) => dispatch({ type: 'connection', state: connection, attempt: detail.attempt }),
      },
      streamOptions,
    )
    return () => {
      handle.stop()
      dispatch({ type: 'connection', state: 'idle' as LiveStreamState, attempt: 0 })
    }
  }, [wantStream, tripId, queryClient])

  // Memoire de session : la fiche rouverte affiche aussitot la derniere position, avec son age.
  const participants = state.participants
  useEffect(() => {
    if (!tripId) return
    if (terminal) clearStoredParticipants(tripId)
    else if (Object.keys(participants).length > 0) storeParticipants(tripId, participants)
  }, [tripId, participants, terminal])

  const offset = state.clockOffsetMs
  const ageOf = useCallback(
    (participant: KnownParticipant, now: number) => participantAgeSeconds(participant, now, offset),
    [offset],
  )
  const refetch = useCallback(() => {
    void snapshot.refetch()
  }, [snapshot])

  const myBookingId = options.myBookingId ?? null
  return useMemo<LiveView>(() => {
    const list = Object.values(participants)
    const driver = participants.driver ?? null
    const me = myBookingId ? (participants[`booking:${myBookingId}`] ?? null) : null
    const passengers = list.filter((p) => p.role === 'PASSENGER' && (isDriver || p.bookingId === myBookingId))
    return {
      snapshot,
      participants,
      driver,
      me,
      passengers,
      connection: state.connection,
      attempt: state.attempt,
      streaming,
      sharingEnabled: sharingKnown,
      tripStatus: state.tripStatus ?? snapshotData?.tripStatus ?? null,
      intervalSeconds: state.intervalSeconds,
      endedReason: state.endedReason,
      ageOf,
      refetch,
    }
  }, [participants, myBookingId, isDriver, snapshot, state.connection, state.attempt, state.tripStatus, state.intervalSeconds, state.endedReason, streaming, sharingKnown, snapshotData?.tripStatus, ageOf, refetch])
}
