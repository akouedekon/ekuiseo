import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/api/client'
import { demoTrip, demoTripSearch, demoTripStops, DEMO_MY_TRIPS, DEMO_RECURRING } from '@/api/demo'
import { resilient, resilientMutation, type Sourced } from '@/api/resilient'
import type { RecurringTripResponse, TripStopResponse } from '@/api/extended'
import type { CreateTripRequest, Page, TripResponse, TripType } from '@/api/types'

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

function toQueryString(params: Record<string, string | number | undefined>): string {
  const usp = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') usp.set(key, String(value))
  }
  return usp.toString()
}

export function useTripSearch(params: TripSearchParams | null) {
  return useQuery<Sourced<Page<TripResponse>>>({
    queryKey: ['trips', 'search', params],
    queryFn: () =>
      resilient(
        () =>
          apiClient.get<Page<TripResponse>>(
            `/api/v1/trips/search?${toQueryString(params as unknown as Record<string, string | number | undefined>)}`,
            { auth: false },
          ),
        demoTripSearch,
      ),
    enabled: !!params,
  })
}

export function useTrip(id: string | undefined) {
  return useQuery<Sourced<TripResponse>>({
    queryKey: ['trips', id],
    queryFn: () => resilient(() => apiClient.get<TripResponse>(`/api/v1/trips/${id}`, { auth: false }), () => demoTrip(id!)),
    enabled: !!id,
  })
}

/**
 * Arrets intermediaires et prix par troncon.
 * ATTENDU : GET /api/v1/trips/{id}/stops
 */
export function useTripStops(id: string | undefined) {
  return useQuery<Sourced<TripStopResponse[]>>({
    queryKey: ['trips', id, 'stops'],
    queryFn: () =>
      resilient(() => apiClient.get<TripStopResponse[]>(`/api/v1/trips/${id}/stops`, { auth: false }), () =>
        demoTripStops(id!),
      ),
    enabled: !!id,
  })
}

export function useMyTrips() {
  return useQuery<Sourced<TripResponse[]>>({
    queryKey: ['me', 'trips'],
    queryFn: () => resilient(() => apiClient.get<TripResponse[]>('/api/v1/me/trips'), () => DEMO_MY_TRIPS),
  })
}

export function useCreateTrip() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: CreateTripRequest) =>
      resilientMutation(
        () => apiClient.post<TripResponse>('/api/v1/trips', input),
        () => ({ ...demoTrip('t-01'), id: `t-local-${Date.now()}` }),
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['me', 'trips'] })
    },
  })
}

export function useCancelTrip() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (tripId: string) =>
      resilientMutation(() => apiClient.delete<void>(`/api/v1/trips/${tripId}`), () => undefined),
    // Mise a jour optimiste : le trajet bascule immediatement en « annule ».
    onMutate: async (tripId) => {
      await queryClient.cancelQueries({ queryKey: ['me', 'trips'] })
      const previous = queryClient.getQueryData<Sourced<TripResponse[]>>(['me', 'trips'])
      if (previous) {
        queryClient.setQueryData<Sourced<TripResponse[]>>(['me', 'trips'], {
          ...previous,
          data: previous.data.map((trip) => (trip.id === tripId ? { ...trip, status: 'CANCELLED' } : trip)),
        })
      }
      return { previous }
    },
    onError: (_error, _tripId, context) => {
      if (context?.previous) queryClient.setQueryData(['me', 'trips'], context.previous)
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['me', 'trips'] })
    },
  })
}

/**
 * Trajets recurrents memorises du passager (bloc « votre trajet de la semaine »).
 * ATTENDU : GET /api/v1/me/recurring-trips
 */
export function useRecurringTrips(enabled: boolean) {
  return useQuery<Sourced<RecurringTripResponse[]>>({
    queryKey: ['me', 'recurring-trips'],
    queryFn: () =>
      resilient(() => apiClient.get<RecurringTripResponse[]>('/api/v1/me/recurring-trips'), () => DEMO_RECURRING),
    enabled,
  })
}
