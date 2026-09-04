import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/api/client'
import { DEMO_CONVERSATIONS, DEMO_ME, demoMessages } from '@/api/demo'
import { resilient, resilientMutation, type Sourced } from '@/api/resilient'
import type { ConversationSummary } from '@/api/extended'
import type { MessageResponse } from '@/api/types'

export function useMessages(bookingId: string | undefined) {
  return useQuery<Sourced<MessageResponse[]>>({
    queryKey: ['bookings', bookingId, 'messages'],
    queryFn: () =>
      resilient(() => apiClient.get<MessageResponse[]>(`/api/v1/bookings/${bookingId}/messages`), () =>
        demoMessages(bookingId!),
      ),
    enabled: !!bookingId,
    refetchInterval: 15_000,
  })
}

/**
 * Liste des conversations (une par reservation).
 * ATTENDU : GET /api/v1/me/conversations
 */
export function useConversations() {
  return useQuery<Sourced<ConversationSummary[]>>({
    queryKey: ['me', 'conversations'],
    queryFn: () =>
      resilient(() => apiClient.get<ConversationSummary[]>('/api/v1/me/conversations'), () => DEMO_CONVERSATIONS),
  })
}

export function useSendMessage(bookingId: string) {
  const queryClient = useQueryClient()
  const key = ['bookings', bookingId, 'messages']

  return useMutation({
    mutationFn: (body: string) =>
      resilientMutation(
        () => apiClient.post<MessageResponse>(`/api/v1/bookings/${bookingId}/messages`, { body }),
        () => ({
          id: `m-local-${Date.now()}`,
          conversationId: bookingId,
          senderId: DEMO_ME.id,
          body,
          readAt: null,
          createdAt: new Date().toISOString(),
        }),
      ),
    /*
     * Envoi optimiste : le message apparait immediatement, marque « en cours »
     * par un identifiant temporaire. Sur reseau coupe, TanStack Query conserve
     * la mutation en file (networkMode offlineFirst) et la rejoue au retour.
     */
    onMutate: async (body) => {
      await queryClient.cancelQueries({ queryKey: key })
      const previous = queryClient.getQueryData<Sourced<MessageResponse[]>>(key)
      const optimistic: MessageResponse = {
        id: `pending-${Date.now()}`,
        conversationId: bookingId,
        senderId: DEMO_ME.id,
        body,
        readAt: null,
        createdAt: new Date().toISOString(),
      }
      queryClient.setQueryData<Sourced<MessageResponse[]>>(key, {
        demo: previous?.demo ?? false,
        data: [...(previous?.data ?? []), optimistic],
      })
      return { previous }
    },
    onError: (_error, _body, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous)
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: key })
    },
  })
}
