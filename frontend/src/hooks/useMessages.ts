import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/api/client'
import { useIsAuthenticated } from '@/hooks/useAuth'
import type { ConversationSummary } from '@/api/extended'
import type { MessageResponse } from '@/api/types'

/** GET /api/v1/bookings/{id}/messages, rafraichi toutes les 15 s (pas de temps reel cote serveur). */
export function useMessages(bookingId: string | undefined) {
  return useQuery<MessageResponse[]>({
    queryKey: ['bookings', bookingId, 'messages'],
    queryFn: () => apiClient.get<MessageResponse[]>(`/api/v1/bookings/${bookingId}/messages`),
    enabled: !!bookingId,
    refetchInterval: 15_000,
  })
}

/** GET /api/v1/me/conversations : une conversation par reservation. */
export function useConversations(enabled = true) {
  return useQuery<ConversationSummary[]>({
    queryKey: ['me', 'conversations'],
    queryFn: () => apiClient.get<ConversationSummary[]>('/api/v1/me/conversations'),
    refetchInterval: 30_000,
    enabled,
  })
}

/** Messages non lus toutes conversations confondues (pastille de la navigation) ; 0 hors session. */
export function useUnreadMessagesCount(): number {
  const authenticated = useIsAuthenticated()
  const { data } = useConversations(authenticated)
  return data?.reduce((sum, conversation) => sum + conversation.unreadCount, 0) ?? 0
}

/**
 * POST /api/v1/bookings/{id}/messages. Envoi optimiste : le message apparait
 * immediatement, marque « en cours » par un identifiant temporaire, attribue
 * a l'utilisateur courant (`senderId`). Sur reseau coupe, TanStack Query
 * conserve la mutation en file (networkMode offlineFirst) et la rejoue au retour.
 */
export function useSendMessage(bookingId: string | undefined, senderId: string | undefined) {
  const queryClient = useQueryClient()
  const key = ['bookings', bookingId, 'messages']

  return useMutation({
    mutationFn: (body: string) => {
      if (!bookingId) return Promise.reject(new Error('Réservation inconnue'))
      return apiClient.post<MessageResponse>(`/api/v1/bookings/${bookingId}/messages`, { body })
    },
    onMutate: async (body) => {
      await queryClient.cancelQueries({ queryKey: key })
      const previous = queryClient.getQueryData<MessageResponse[]>(key)
      const optimistic: MessageResponse = {
        id: `pending-${Date.now()}`,
        conversationId: bookingId ?? '',
        senderId: senderId ?? '',
        body,
        readAt: null,
        createdAt: new Date().toISOString(),
      }
      queryClient.setQueryData<MessageResponse[]>(key, [...(previous ?? []), optimistic])
      return { previous }
    },
    onError: (_error, _body, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous)
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: key })
      queryClient.invalidateQueries({ queryKey: ['me', 'conversations'] })
    },
  })
}
