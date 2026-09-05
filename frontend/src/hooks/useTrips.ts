import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/api/client'
import type { PopularRouteResponse, RecurringTripResponse, TripStopResponse, UpdateTripRequest } from '@/api/extended'
import type { BookingResponse, CreateTripRequest, Page, TripBookingResponse, TripResponse, TripType } from '@/api/types'

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
  page?: number
  size?: number
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
export function searchTripsRequest(params: TripSearchParams, page?: number): Promise<Page<TripResponse>> {
  const query = page === undefined ? params : { ...params, page }
  return apiClient.get<Page<TripResponse>>(`/api/v1/trips/search?${toQueryString(query)}`)
}

/** GET /api/v1/trips/search (public, pagine cote serveur). */
export function useTripSearch(params: TripSearchParams | null) {
  return useQuery<Page<TripResponse>>({
    queryKey: ['trips', 'search', params],
    queryFn: () => searchTripsRequest(params as TripSearchParams),
    enabled: !!params,
  })
}

/**
 * Meme recherche, paginee cote serveur et cumulee page apres page (« Voir plus »).
 * La cle ignore `page` : c'est le parametre de page qui varie.
 */
export function useTripSearchPages(params: TripSearchParams | null) {
  return useInfiniteQuery<Page<TripResponse>>({
    queryKey: ['trips', 'search', 'pages', params],
    queryFn: ({ pageParam }) => searchTripsRequest(params as TripSearchParams, pageParam as number),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.number + 1 < last.totalPages ? last.number + 1 : undefined),
    enabled: !!params,
  })
}

/** GET /api/v1/trips/popular : axes les plus proposes en ce moment (public). */
export function usePopularRoutes(limit = 4) {
  return useQuery<PopularRouteResponse[]>({
    queryKey: ['trips', 'popular', limit],
    queryFn: () => apiClient.get<PopularRouteResponse[]>(`/api/v1/trips/popular?limit=${limit}`, { auth: false }),
    staleTime: 10 * 60_000,
  })
}

/** GET /api/v1/trips/{id} (public si publie). */
export function useTrip(id: string | undefined) {
  return useQuery<TripResponse>({
    queryKey: ['trips', id],
    queryFn: () => apiClient.get<TripResponse>(`/api/v1/trips/${id}`, { auth: false }),
    enabled: !!id,
  })
}

/** GET /api/v1/trips/{id}/stops : arrets intermediaires et prix par troncon. */
export function useTripStops(id: string | undefined) {
  return useQuery<TripStopResponse[]>({
    queryKey: ['trips', id, 'stops'],
    queryFn: () => apiClient.get<TripStopResponse[]>(`/api/v1/trips/${id}/stops`, { auth: false }),
    enabled: !!id,
  })
}

/** GET /api/v1/me/trips : trajets publies par l'utilisateur (conducteur). */
export function useMyTrips(enabled = true) {
  return useQuery<TripResponse[]>({
    queryKey: ['me', 'trips'],
    queryFn: () => apiClient.get<TripResponse[]>('/api/v1/me/trips'),
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
      queryClient.invalidateQueries({ queryKey: ['trips', 'popular'] })
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
    },
  })
}

/** GET /api/v1/me/recurring-trips : navettes memorisees du passager (bloc « votre trajet de la semaine »). */
export function useRecurringTrips(enabled: boolean) {
  return useQuery<RecurringTripResponse[]>({
    queryKey: ['me', 'recurring-trips'],
    queryFn: () => apiClient.get<RecurringTripResponse[]>('/api/v1/me/recurring-trips'),
    enabled,
  })
}

/** GET /api/v1/trips/{id}/bookings : passagers d un trajet que je conduis (appel au depart, no-show). */
export function useTripPassengers(tripId: string | null) {
  return useQuery({
    queryKey: ['trips', tripId, 'passengers'],
    queryFn: () => apiClient.get<TripBookingResponse[]>(`/api/v1/trips/${tripId}/bookings`),
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
