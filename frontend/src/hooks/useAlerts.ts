import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/api/client'
import type { TripAlertRequest, TripAlertResponse } from '@/api/extended'

/**
 * POST /api/v1/trip-alerts : prevenir le passager quand une offre correspond a sa
 * recherche. 200 si une alerte identique existait deja (reutilisee), 422 au-dela
 * de 10 alertes actives.
 */
export function useCreateTripAlert() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: TripAlertRequest) => apiClient.post<TripAlertResponse>('/api/v1/trip-alerts', input),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['me', 'trip-alerts'] }),
  })
}

/** GET /api/v1/trip-alerts : alertes du compte, les actives en premier (section « Mes alertes »). */
export function useMyTripAlerts(enabled = true) {
  return useQuery<TripAlertResponse[]>({
    queryKey: ['me', 'trip-alerts'],
    queryFn: () => apiClient.get<TripAlertResponse[]>('/api/v1/trip-alerts'),
    enabled,
  })
}

/** DELETE /api/v1/trip-alerts/{id} (204), retrait optimiste. */
export function useDeleteTripAlert() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => apiClient.delete<void>(`/api/v1/trip-alerts/${id}`),
    onMutate: async (id) => {
      await queryClient.cancelQueries({ queryKey: ['me', 'trip-alerts'] })
      const previous = queryClient.getQueryData<TripAlertResponse[]>(['me', 'trip-alerts'])
      if (previous) {
        queryClient.setQueryData<TripAlertResponse[]>(
          ['me', 'trip-alerts'],
          previous.filter((alert) => alert.id !== id),
        )
      }
      return { previous }
    },
    onError: (_error, _id, context) => {
      if (context?.previous) queryClient.setQueryData(['me', 'trip-alerts'], context.previous)
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['me', 'trip-alerts'] }),
  })
}
