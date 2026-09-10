import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { apiClient } from '@/api/client'
import type { PopularRouteResponse, RecurringTripResponse, TripStopResponse, UpdateTripRequest } from '@/api/extended'
import type {
  BookingResponse,
  CreateTripRequest,
  DeclineBookingRequest,
  LivePositionRequest,
  LivePositionResponse,
  LiveSharingResponse,
  Page,
  PublicLiveResponse,
  TripBookingResponse,
  TripResponse,
  TripType,
  VehicleType,
} from '@/api/types'
import { LIVE_REFRESH_INTERVAL_MS } from '@/lib/liveTracking'

export interface TripSearchParams {
  originLat: number
  originLng: number
  destLat: number
  destLng: number
  /** Libelles tapes par le passager : ne filtrent rien, ils lisibilisent la trace de recherche cote back-office. */
  originLabel?: string
  destLabel?: string
  date?: string
  seats?: number
  radiusKm?: number
  tripType?: TripType
  /* Tri et filtres appliques PAR LE SERVEUR sur l'ensemble des resultats (audit F137) : jamais sur la page chargee. */
  sort?: TripSearchSort
  maxPrice?: number
  minRating?: number
  verifiedOnly?: boolean
  /** Voiture, moto ou tricycle (V22) ; absent = tous. */
  vehicleType?: VehicleType
  page?: number
  size?: number
}

export type TripSearchSort = 'departure' | 'price' | 'rating'

/** Cle racine des resultats de recherche : invalidee des qu'un trajet change (audit F153). */
export const TRIP_SEARCH_KEY = ['trips', 'search'] as const

/** Les listes de recherche et les axes populaires sont perimes des qu'un trajet est cree, modifie, annule ou reserve. */
export function invalidateTripListings(queryClient: QueryClient): void {
  queryClient.invalidateQueries({ queryKey: TRIP_SEARCH_KEY })
  queryClient.invalidateQueries({ queryKey: ['trips', 'popular'] })
}

function toQueryString(params: object): string {
  const usp = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') usp.set(key, String(value))
  }
  return usp.toString()
}

/**
 * GET /api/v1/trips/search. L'endpoint est public, mais le jeton part s'il existe :
 * c'est lui qui rattache la recherche a l'utilisateur dans `search_events`, sans quoi
 * le taux recherche -> reservation du back-office vaut structurellement 0.
 */
export function searchTripsRequest(params: TripSearchParams, page?: number, signal?: AbortSignal): Promise<Page<TripResponse>> {
  const query = page === undefined ? params : { ...params, page }
  return apiClient.get<Page<TripResponse>>(`/api/v1/trips/search?${toQueryString(query)}`, { signal })
}

/**
 * Recherche paginee cote serveur et cumulee page apres page (« Voir plus »).
 * La cle ignore `page` : c'est le parametre de page qui varie.
 */
export function useTripSearchPages(params: TripSearchParams | null) {
  return useInfiniteQuery<Page<TripResponse>>({
    queryKey: [...TRIP_SEARCH_KEY, 'pages', params],
    queryFn: ({ pageParam, signal }) => searchTripsRequest(params as TripSearchParams, pageParam as number, signal),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.number + 1 < last.totalPages ? last.number + 1 : undefined),
    enabled: !!params,
  })
}

/** GET /api/v1/trips/popular : axes les plus proposes en ce moment (public). */
export function usePopularRoutes(limit = 4) {
  return useQuery<PopularRouteResponse[]>({
    queryKey: ['trips', 'popular', limit],
    queryFn: ({ signal }) =>
      apiClient.get<PopularRouteResponse[]>(`/api/v1/trips/popular?limit=${limit}`, { auth: false, signal }),
    staleTime: 10 * 60_000,
  })
}

/** GET /api/v1/trips/{id} (public si publie). */
export function useTrip(id: string | undefined) {
  return useQuery<TripResponse>({
    queryKey: ['trips', id],
    queryFn: ({ signal }) => apiClient.get<TripResponse>(`/api/v1/trips/${id}`, { auth: false, signal }),
    enabled: !!id,
  })
}

/** GET /api/v1/trips/{id}/stops : arrets intermediaires et prix par troncon. */
export function useTripStops(id: string | undefined) {
  return useQuery<TripStopResponse[]>({
    queryKey: ['trips', id, 'stops'],
    queryFn: ({ signal }) => apiClient.get<TripStopResponse[]>(`/api/v1/trips/${id}/stops`, { auth: false, signal }),
    enabled: !!id,
  })
}

/** GET /api/v1/me/trips : trajets publies par l'utilisateur (conducteur). */
export function useMyTrips(enabled = true) {
  return useQuery<TripResponse[]>({
    queryKey: ['me', 'trips'],
    queryFn: ({ signal }) => apiClient.get<TripResponse[]>('/api/v1/me/trips', { signal }),
    enabled,
  })
}

/** POST /api/v1/trips */
export function useCreateTrip() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: CreateTripRequest) => apiClient.post<TripResponse>('/api/v1/trips', input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['me', 'trips'] })
      invalidateTripListings(queryClient)
    },
  })
}

/** PATCH /api/v1/trips/{id} : modification par le conducteur (horaire, places, prix, texte). */
export function useUpdateTrip() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: UpdateTripRequest }) =>
      apiClient.patch<TripResponse>(`/api/v1/trips/${id}`, input),
    onSuccess: (trip) => {
      queryClient.setQueryData<TripResponse>(['trips', trip.id], trip)
      queryClient.invalidateQueries({ queryKey: ['me', 'trips'] })
      // Un horaire ou un prix modifie change les resultats de recherche deja affiches.
      invalidateTripListings(queryClient)
    },
  })
}

/** DELETE /api/v1/trips/{id} : annulation par le conducteur, bascule optimiste. */
export function useCancelTrip() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (tripId: string) => apiClient.delete<void>(`/api/v1/trips/${tripId}`),
    onMutate: async (tripId) => {
      await queryClient.cancelQueries({ queryKey: ['me', 'trips'] })
      const previous = queryClient.getQueryData<TripResponse[]>(['me', 'trips'])
      if (previous) {
        queryClient.setQueryData<TripResponse[]>(
          ['me', 'trips'],
          previous.map((trip) => (trip.id === tripId ? { ...trip, status: 'CANCELLED' } : trip)),
        )
      }
      return { previous }
    },
    onError: (_error, _tripId, context) => {
      if (context?.previous) queryClient.setQueryData(['me', 'trips'], context.previous)
    },
    onSettled: (_data, _error, tripId) => {
      queryClient.invalidateQueries({ queryKey: ['me', 'trips'] })
      queryClient.invalidateQueries({ queryKey: ['trips', tripId] })
      invalidateTripListings(queryClient)
    },
  })
}

/** GET /api/v1/me/recurring-trips : navettes memorisees du passager (bloc « votre trajet de la semaine »). */
export function useRecurringTrips(enabled: boolean) {
  return useQuery<RecurringTripResponse[]>({
    queryKey: ['me', 'recurring-trips'],
    queryFn: ({ signal }) => apiClient.get<RecurringTripResponse[]>('/api/v1/me/recurring-trips', { signal }),
    enabled,
  })
}

/** GET /api/v1/trips/{id}/bookings : passagers d un trajet que je conduis (appel au depart, no-show). */
export function useTripPassengers(tripId: string | null) {
  return useQuery({
    queryKey: ['trips', tripId, 'passengers'],
    queryFn: ({ signal }) => apiClient.get<TripBookingResponse[]>(`/api/v1/trips/${tripId}/bookings`, { signal }),
    enabled: tripId !== null,
    staleTime: 30_000,
  })
}

/** POST /api/v1/bookings/{id}/no-show : le conducteur signale l absence d un passager (jusqu a 48 h apres le depart). */
export function useMarkNoShow() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ bookingId }: { bookingId: string; tripId: string }) =>
      apiClient.post<BookingResponse>(`/api/v1/bookings/${bookingId}/no-show`),
    onSuccess: (_result, { tripId }) => {
      queryClient.invalidateQueries({ queryKey: ['trips', tripId, 'passengers'] })
    },
  })
}

/**
 * POST /api/v1/bookings/{id}/accept | /decline { reason? } (V19) : reponse du conducteur a une
 * demande sur un trajet sans reservation immediate. Un refus libere les places (le trajet et
 * les resultats de recherche changent) et rembourse integralement l'acompte.
 */
export function useRespondToBooking() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ bookingId, accept, reason }: { bookingId: string; tripId: string; accept: boolean; reason?: string }) =>
      accept
        ? apiClient.post<BookingResponse>(`/api/v1/bookings/${bookingId}/accept`)
        : apiClient.post<BookingResponse>(`/api/v1/bookings/${bookingId}/decline`, reason ? ({ reason } satisfies DeclineBookingRequest) : undefined),
    onSuccess: (_result, { tripId, accept }) => {
      queryClient.invalidateQueries({ queryKey: ['trips', tripId, 'passengers'] })
      if (!accept) {
        queryClient.invalidateQueries({ queryKey: ['trips', tripId] })
        queryClient.invalidateQueries({ queryKey: ['me', 'trips'] })
        invalidateTripListings(queryClient)
      }
    },
  })
}

/* ------------------------------------------------------------ Suivi en direct (V23) */

/**
 * GET /api/v1/trips/{id}/live : derniere position du vehicule, pour le conducteur et ses
 * passagers. Interroge toutes les 10 s tant que `live` est vrai (fenetre du trajet ouverte
 * et partage actif), jamais en arriere-plan. Un 403 (visiteur sans reservation) est
 * definitif : pas de reessai.
 */
export function useTripLive(tripId: string | undefined, options: { enabled?: boolean; live?: boolean } = {}) {
  const enabled = (options.enabled ?? true) && !!tripId
  return useQuery<LivePositionResponse>({
    queryKey: ['trips', tripId, 'live'],
    queryFn: ({ signal }) => apiClient.get<LivePositionResponse>(`/api/v1/trips/${tripId}/live`, { signal }),
    enabled,
    staleTime: 5_000,
    refetchInterval: (query) => (options.live && query.state.data?.enabled ? LIVE_REFRESH_INTERVAL_MS : false),
    refetchIntervalInBackground: false,
  })
}

/** PUT /api/v1/trips/{id}/live { enabled } : le conducteur active ou coupe le partage. */
export function useSetLiveSharing() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ tripId, enabled }: { tripId: string; enabled: boolean }) =>
      apiClient.put<LiveSharingResponse>(`/api/v1/trips/${tripId}/live`, { enabled }),
    onSuccess: (_result, { tripId }) => {
      queryClient.invalidateQueries({ queryKey: ['trips', tripId, 'live'] })
    },
  })
}

/** POST /api/v1/trips/{id}/live/positions : une position du conducteur (202, sans corps). Jamais de reessai : la suivante arrive dans 10 s. */
export function usePostLivePosition() {
  return useMutation({
    mutationFn: ({ tripId, position }: { tripId: string; position: LivePositionRequest }) =>
      apiClient.post<void>(`/api/v1/trips/${tripId}/live/positions`, position),
    retry: false,
  })
}

/**
 * GET /api/v1/live/{token} : suivi public, sans compte. Interroge toutes les 10 s tant que
 * le trajet n est pas termine ; un 404 (lien coupe ou expire) est definitif.
 */
export function usePublicLive(token: string | undefined) {
  return useQuery<PublicLiveResponse>({
    queryKey: ['live', token],
    queryFn: ({ signal }) => apiClient.get<PublicLiveResponse>(`/api/v1/live/${token}`, { auth: false, signal }),
    enabled: !!token,
    staleTime: 5_000,
    refetchInterval: (query) => {
      const status = query.state.data?.tripStatus
      return status && status !== 'COMPLETED' && status !== 'CANCELLED' ? LIVE_REFRESH_INTERVAL_MS : false
    },
    refetchIntervalInBackground: false,
  })
}
