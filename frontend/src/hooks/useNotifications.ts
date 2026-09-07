import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type InfiniteData } from '@tanstack/react-query'
import { apiClient } from '@/api/client'
import type { NotificationResponse, Page, UnreadCountResponse } from '@/api/types'
import { useIsAuthenticated } from '@/hooks/useAuth'

const PAGE_SIZE = 50

/**
 * GET /api/v1/notifications?page=&size=50 : liste paginee, chargee uniquement
 * sur l'ecran des notifications (« Voir plus » pour la page suivante). La
 * pastille de l'en-tete ne lit que le compteur (useUnreadNotificationCount) :
 * plus de rechargement integral de la liste toutes les 60 s sur tous les
 * ecrans (audit F231).
 */
export function useNotifications(enabled = true) {
  const authenticated = useIsAuthenticated()
  return useInfiniteQuery<Page<NotificationResponse>>({
    queryKey: ['notifications', 'list'],
    queryFn: ({ pageParam }) =>
      apiClient.get<Page<NotificationResponse>>(`/api/v1/notifications?page=${pageParam as number}&size=${PAGE_SIZE}`),
    initialPageParam: 0,
    getNextPageParam: (last) => {
      if (last.last === true) return undefined
      if (typeof last.totalPages === 'number') return last.number + 1 < last.totalPages ? last.number + 1 : undefined
      return last.content.length < last.size ? undefined : last.number + 1
    },
    enabled: enabled && authenticated,
    staleTime: 30_000,
  })
}

/** GET /api/v1/notifications/unread-count -> { count }, rafraichi chaque minute tant que l'onglet est ouvert. */
export function useUnreadNotificationCount(): number {
  const authenticated = useIsAuthenticated()
  const { data } = useQuery<UnreadCountResponse>({
    queryKey: ['notifications', 'unread-count'],
    queryFn: ({ signal }) => apiClient.get<UnreadCountResponse>('/api/v1/notifications/unread-count', { signal }),
    enabled: authenticated,
    refetchInterval: 60_000,
    staleTime: 30_000,
  })
  return data?.count ?? 0
}

type NotificationPages = InfiniteData<Page<NotificationResponse>>

function patchPages(
  pages: NotificationPages | undefined,
  update: (notification: NotificationResponse) => NotificationResponse,
): NotificationPages | undefined {
  if (!pages) return pages
  return {
    ...pages,
    pages: pages.pages.map((page) => ({ ...page, content: page.content.map(update) })),
  }
}

/** POST /api/v1/notifications/{id}/read, bascule optimiste sur la liste et le compteur. */
export function useMarkNotificationRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => apiClient.post<void>(`/api/v1/notifications/${id}/read`),
    onMutate: async (id) => {
      await queryClient.cancelQueries({ queryKey: ['notifications'] })
      const previousList = queryClient.getQueryData<NotificationPages>(['notifications', 'list'])
      const previousCount = queryClient.getQueryData<UnreadCountResponse>(['notifications', 'unread-count'])
      const wasUnread = previousList?.pages.some((page) => page.content.some((n) => n.id === id && !n.readAt)) ?? false
      queryClient.setQueryData<NotificationPages>(['notifications', 'list'], (pages) =>
        patchPages(pages, (n) => (n.id === id && !n.readAt ? { ...n, readAt: new Date().toISOString() } : n)),
      )
      if (wasUnread && previousCount) {
        queryClient.setQueryData<UnreadCountResponse>(['notifications', 'unread-count'], {
          count: Math.max(0, previousCount.count - 1),
        })
      }
      return { previousList, previousCount }
    },
    onError: (_e, _id, context) => {
      if (context?.previousList) queryClient.setQueryData(['notifications', 'list'], context.previousList)
      if (context?.previousCount) queryClient.setQueryData(['notifications', 'unread-count'], context.previousCount)
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['notifications', 'unread-count'] }),
  })
}

/** POST /api/v1/notifications/read-all, bascule optimiste. */
export function useMarkAllNotificationsRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiClient.post<void>('/api/v1/notifications/read-all'),
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: ['notifications'] })
      const previousList = queryClient.getQueryData<NotificationPages>(['notifications', 'list'])
      const previousCount = queryClient.getQueryData<UnreadCountResponse>(['notifications', 'unread-count'])
      const now = new Date().toISOString()
      queryClient.setQueryData<NotificationPages>(['notifications', 'list'], (pages) =>
        patchPages(pages, (n) => (n.readAt ? n : { ...n, readAt: now })),
      )
      queryClient.setQueryData<UnreadCountResponse>(['notifications', 'unread-count'], { count: 0 })
      return { previousList, previousCount }
    },
    onError: (_e, _v, context) => {
      if (context?.previousList) queryClient.setQueryData(['notifications', 'list'], context.previousList)
      if (context?.previousCount) queryClient.setQueryData(['notifications', 'unread-count'], context.previousCount)
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  })
}
