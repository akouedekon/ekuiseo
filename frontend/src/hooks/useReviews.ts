import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/api/client'
import { demoPublicUser, demoReviews } from '@/api/demo'
import { resilient, resilientMutation, type Sourced } from '@/api/resilient'
import type { PublicUserResponse } from '@/api/extended'
import type { CreateReviewRequest, ReviewResponse } from '@/api/types'

export function useUserReviews(userId: string | undefined) {
  return useQuery<Sourced<ReviewResponse[]>>({
    queryKey: ['users', userId, 'reviews'],
    queryFn: () =>
      resilient(() => apiClient.get<ReviewResponse[]>(`/api/v1/users/${userId}/reviews`, { auth: false }), () =>
        demoReviews(userId!),
      ),
    enabled: !!userId,
  })
}

/**
 * Profil public d'un conducteur (statistiques, badges, vehicules).
 * ATTENDU : GET /api/v1/users/{id}
 */
export function usePublicUser(userId: string | undefined) {
  return useQuery<Sourced<PublicUserResponse>>({
    queryKey: ['users', userId],
    queryFn: () =>
      resilient(() => apiClient.get<PublicUserResponse>(`/api/v1/users/${userId}`, { auth: false }), () =>
        demoPublicUser(userId!),
      ),
    enabled: !!userId,
  })
}

export function useCreateReview(tripId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: CreateReviewRequest) =>
      resilientMutation(
        () => apiClient.post<ReviewResponse>(`/api/v1/trips/${tripId}/reviews`, input),
        () => ({
          id: `rv-local-${Date.now()}`,
          tripId,
          authorId: 'u-demo-me',
          targetId: input.targetId,
          role: input.role,
          rating: input.rating,
          comment: input.comment ?? null,
          createdAt: new Date().toISOString(),
        }),
      ),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['users', variables.targetId, 'reviews'] })
    },
  })
}
