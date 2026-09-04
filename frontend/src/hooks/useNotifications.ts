import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/api/client'
import type { NotificationResponse } from '@/api/types'
import { useIsAuthenticated } from '@/hooks/useAuth'

/** GET /api/v1/notifications, sondage toutes les 60 s (pas de push cote serveur). */
export function useNotifications() {
  const authenticated = useIsAuthenticated()
  return useQuery<NotificationResponse[]>({
    queryKey: ['notifications'],
    queryFn: () => apiClient.get<NotificationResponse[]>('/api/v1/notifications'),
    enabled: authenticated,
    refetchInterval: 60_000,
  })
}

export function useUnreadNotificationCount(): number {
  const { data } = useNotifications()
  return data?.filter((n) => !n.readAt).length ?? 0
}

/** POST /api/v1/notifications/{id}/read, bascule optimiste. */
export function useMarkNotificationRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => apiClient.post<void>(`/api/v1/notifications/${id}/read`),
    onMutate: async (id) => {
      await queryClient.cancelQueries({ queryKey: ['notifications'] })
      const previous = queryClient.getQueryData<NotificationResponse[]>(['notifications'])
      if (previous) {
        queryClient.setQueryData<NotificationResponse[]>(
          ['notifications'],
          previous.map((n) => (n.id === id ? { ...n, readAt: new Date().toISOString() } : n)),
        )
      }
      return { previous }
    },
    onError: (_e, _id, context) => {
      if (context?.previous) queryClient.setQueryData(['notifications'], context.previous)
    },
  })
}

/** POST /api/v1/notifications/read-all, bascule optimiste. */
export function useMarkAllNotificationsRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiClient.post<void>('/api/v1/notifications/read-all'),
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: ['notifications'] })
      const previous = queryClient.getQueryData<NotificationResponse[]>(['notifications'])
      if (previous) {
        const now = new Date().toISOString()
        queryClient.setQueryData<NotificationResponse[]>(
          ['notifications'],
          previous.map((n) => (n.readAt ? n : { ...n, readAt: now })),
        )
      }
      return { previous }
    },
    onError: (_e, _v, context) => {
      if (context?.previous) queryClient.setQueryData(['notifications'], context.previous)
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  })
}
