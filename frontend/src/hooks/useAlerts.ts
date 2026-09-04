import { useMutation } from '@tanstack/react-query'
import { apiClient } from '@/api/client'
import type { TripAlertRequest, TripAlertResponse } from '@/api/extended'

/** POST /api/v1/trip-alerts : prevenir le passager quand une offre correspond a sa recherche. */
export function useCreateTripAlert() {
  return useMutation({
    mutationFn: (input: TripAlertRequest) => apiClient.post<TripAlertResponse>('/api/v1/trip-alerts', input),
  })
}
