import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/api/client'
import { DEMO_NOTIFICATIONS } from '@/api/demo'
import { resilient, resilientMutation, type Sourced } from '@/api/resilient'
import type { NotificationResponse } from '@/api/types'
import { isAuthenticated } from '@/hooks/useAuth'

export function useNotifications() {
  return useQuery<Sourced<NotificationResponse[]>>({
    queryKey: ['notifications'],
    queryFn: () =>
      resilient(() => apiClient.get<NotificationResponse[]>('/api/v1/notifications'), () => DEMO_NOTIFICATIONS),
    enabled: isAuthenticated(),
    refetchInterval: 60_000,
  })
}

export function useUnreadNotificationCount(): number {
  const { data } = useNotifications()
  return data?.data.filter((n) => !n.readAt).length ?? 0
}

/**
 * Marquage comme lu.
 * ATTENDU : POST /api/v1/notifications/{id}/read
 *           POST /api/v1/notifications/read-all
 */
export function useMarkNotificationRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      resilientMutation(() => apiClient.post<void>(`/api/v1/notifications/${id}/read`), () => undefined),
    onMutate: async (id) => {
      await queryClient.cancelQueries({ queryKey: ['notifications'] })
      const previous = queryClient.getQueryData<Sourced<NotificationResponse[]>>(['notifications'])
      if (previous) {
        queryClient.setQueryData<Sourced<NotificationResponse[]>>(['notifications'], {
          ...previous,
          data: previous.data.map((n) => (n.id === id ? { ...n, readAt: new Date().toISOString() } : n)),
        })
      }
      return { previous }
    },
    onError: (_e, _id, context) => {
      if (context?.previous) queryClient.setQueryData(['notifications'], context.previous)
    },
  })
}

export function useMarkAllNotificationsRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      resilientMutation(() => apiClient.post<void>('/api/v1/notifications/read-all'), () => undefined),
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: ['notifications'] })
      const previous = queryClient.getQueryData<Sourced<NotificationResponse[]>>(['notifications'])
      if (previous) {
        const now = new Date().toISOString()
        queryClient.setQueryData<Sourced<NotificationResponse[]>>(['notifications'], {
          ...previous,
          data: previous.data.map((n) => (n.readAt ? n : { ...n, readAt: now })),
        })
      }
      return { previous }
    },
    onError: (_e, _v, context) => {
      if (context?.previous) queryClient.setQueryData(['notifications'], context.previous)
    },
  })
}
