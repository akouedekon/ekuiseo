import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/api/client'
import type { CreateReportRequest, PublicUserResponse, ReportResponse } from '@/api/extended'
import type { CreateReviewRequest, ReviewResponse } from '@/api/types'

/** GET /api/v1/users/{id}/reviews (public). */
export function useUserReviews(userId: string | undefined) {
  return useQuery<ReviewResponse[]>({
    queryKey: ['users', userId, 'reviews'],
    queryFn: () => apiClient.get<ReviewResponse[]>(`/api/v1/users/${userId}/reviews`, { auth: false }),
    enabled: !!userId,
  })
}

/** GET /api/v1/users/{id} : profil public (statistiques, badges, vehicules). */
export function usePublicUser(userId: string | undefined) {
  return useQuery<PublicUserResponse>({
    queryKey: ['users', userId],
    queryFn: () => apiClient.get<PublicUserResponse>(`/api/v1/users/${userId}`, { auth: false }),
    enabled: !!userId,
  })
}

/** POST /api/v1/trips/{id}/reviews : un avis par trajet et par cible, apres un trajet termine. */
export function useCreateReview() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ tripId, input }: { tripId: string; input: CreateReviewRequest }) =>
      apiClient.post<ReviewResponse>(`/api/v1/trips/${tripId}/reviews`, input),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['users', variables.input.targetId] })
      queryClient.invalidateQueries({ queryKey: ['bookings'] })
      queryClient.invalidateQueries({ queryKey: ['me', 'trips'] })
    },
  })
}

/** POST /api/v1/reports : signalement d'un utilisateur ou d'un trajet, traite par le back-office. */
export function useCreateReport() {
  return useMutation({
    mutationFn: (input: CreateReportRequest) => apiClient.post<ReportResponse>('/api/v1/reports', input),
  })
}
