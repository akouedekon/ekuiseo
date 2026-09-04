import { useMutation, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/api/client'
import { resilientMutation } from '@/api/resilient'
import type { TripAlertRequest, TripAlertResponse } from '@/api/extended'

/**
 * Alerte de trajet : prevenir le passager quand une offre correspond.
 * ATTENDU : POST /api/v1/trip-alerts
 */
export function useCreateTripAlert() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: TripAlertRequest) =>
      resilientMutation(
        () => apiClient.post<TripAlertResponse>('/api/v1/trip-alerts', input),
        () => ({ ...input, id: `al-local-${Date.now()}`, createdAt: new Date().toISOString(), active: true }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['trip-alerts'] }),
  })
}
