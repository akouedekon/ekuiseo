import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient, downloadFile } from '@/api/client'
import {
  DEMO_ADMIN_PAYOUTS,
  DEMO_ADMIN_REPORTS,
  DEMO_ADMIN_USERS,
  DEMO_ADMIN_VERIFICATIONS,
  demoAdminLiquidity,
  demoAdminStats,
} from '@/api/demo'
import { resilient, resilientMutation, type Sourced } from '@/api/resilient'
import type {
  AdminLiquidityResponse,
  AdminPayoutResponse,
  AdminReportResponse,
  AdminStatsResponse,
  AdminUserResponse,
  AdminVerificationResponse,
  ReportStatus,
} from '@/api/extended'

/*
 * Back-office. Tous ces endpoints sont ATTENDUS du backend (prefixe
 * /api/v1/admin, reserve au role ADMIN cote serveur). Le front n'accorde
 * aucun droit par lui-meme : il se contente d'afficher ce que l'API autorise.
 */

export function useAdminStats(days: number) {
  return useQuery<Sourced<AdminStatsResponse>>({
    queryKey: ['admin', 'stats', days],
    queryFn: () =>
      resilient(() => apiClient.get<AdminStatsResponse>(`/api/v1/admin/stats?days=${days}`), () =>
        demoAdminStats(days),
      ),
    staleTime: 5 * 60_000,
  })
}

/** Indicateurs de liquidite et metrique nord : GET /api/v1/admin/stats/liquidity?days=N */
export function useAdminLiquidity(days: number) {
  return useQuery<Sourced<AdminLiquidityResponse>>({
    queryKey: ['admin', 'liquidity', days],
    queryFn: () =>
      resilient(
        () => apiClient.get<AdminLiquidityResponse>(`/api/v1/admin/stats/liquidity?days=${days}`),
        () => demoAdminLiquidity(days),
      ),
    staleTime: 5 * 60_000,
  })
}

/** Export tableur des memes indicateurs : GET /api/v1/admin/stats/liquidity/export?days=N (CSV). */
export function downloadLiquidityCsv(days: number): Promise<void> {
  return downloadFile(`/api/v1/admin/stats/liquidity/export?days=${days}`, `liquidite-${days}j.csv`)
}

export function useAdminReports(status: ReportStatus | 'ALL') {
  return useQuery<Sourced<AdminReportResponse[]>>({
    queryKey: ['admin', 'reports', status],
    queryFn: () =>
      resilient(
        () =>
          apiClient.get<AdminReportResponse[]>(
            `/api/v1/admin/reports${status === 'ALL' ? '' : `?status=${status}`}`,
          ),
        () => (status === 'ALL' ? DEMO_ADMIN_REPORTS : DEMO_ADMIN_REPORTS.filter((r) => r.status === status)),
      ),
  })
}

/** ATTENDU : PATCH /api/v1/admin/reports/{id} { status } */
export function useUpdateReportStatus() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: ReportStatus }) =>
      resilientMutation(
        () => apiClient.patch<AdminReportResponse>(`/api/v1/admin/reports/${id}`, { status }),
        () => ({ ...DEMO_ADMIN_REPORTS[0], id, status }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'reports'] }),
  })
}

export function useAdminVerifications() {
  return useQuery<Sourced<AdminVerificationResponse[]>>({
    queryKey: ['admin', 'verifications'],
    queryFn: () =>
      resilient(
        () => apiClient.get<AdminVerificationResponse[]>('/api/v1/admin/verifications?status=PENDING'),
        () => DEMO_ADMIN_VERIFICATIONS,
      ),
  })
}

/** ATTENDU : POST /api/v1/admin/verifications/{id}/approve | /reject */
export function useReviewVerification() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, approve, reason }: { id: string; approve: boolean; reason?: string }) =>
      resilientMutation(
        () =>
          apiClient.post<void>(
            `/api/v1/admin/verifications/${id}/${approve ? 'approve' : 'reject'}`,
            approve ? undefined : { reason },
          ),
        () => undefined,
      ),
    // Retrait optimiste de la file de moderation.
    onMutate: async ({ id }) => {
      await queryClient.cancelQueries({ queryKey: ['admin', 'verifications'] })
      const previous = queryClient.getQueryData<Sourced<AdminVerificationResponse[]>>(['admin', 'verifications'])
      if (previous) {
        queryClient.setQueryData<Sourced<AdminVerificationResponse[]>>(['admin', 'verifications'], {
          ...previous,
          data: previous.data.filter((v) => v.id !== id),
        })
      }
      return { previous }
    },
    onError: (_e, _v, context) => {
      if (context?.previous) queryClient.setQueryData(['admin', 'verifications'], context.previous)
    },
  })
}

export function useAdminPayouts() {
  return useQuery<Sourced<AdminPayoutResponse[]>>({
    queryKey: ['admin', 'payouts'],
    queryFn: () =>
      resilient(() => apiClient.get<AdminPayoutResponse[]>('/api/v1/admin/payouts'), () => DEMO_ADMIN_PAYOUTS),
  })
}

/** ATTENDU : POST /api/v1/admin/payouts/{id}/pay */
export function useMarkPayoutPaid() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      resilientMutation(() => apiClient.post<AdminPayoutResponse>(`/api/v1/admin/payouts/${id}/pay`), () => ({
        ...DEMO_ADMIN_PAYOUTS[0],
        id,
        status: 'PAID' as const,
        paidAt: new Date().toISOString(),
      })),
    onMutate: async (id) => {
      await queryClient.cancelQueries({ queryKey: ['admin', 'payouts'] })
      const previous = queryClient.getQueryData<Sourced<AdminPayoutResponse[]>>(['admin', 'payouts'])
      if (previous) {
        queryClient.setQueryData<Sourced<AdminPayoutResponse[]>>(['admin', 'payouts'], {
          ...previous,
          data: previous.data.map((p) =>
            p.id === id ? { ...p, status: 'PROCESSING' as const } : p,
          ),
        })
      }
      return { previous }
    },
    onError: (_e, _id, context) => {
      if (context?.previous) queryClient.setQueryData(['admin', 'payouts'], context.previous)
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['admin', 'payouts'] }),
  })
}

export function useAdminUsers(query: string) {
  return useQuery<Sourced<AdminUserResponse[]>>({
    queryKey: ['admin', 'users', query],
    queryFn: () =>
      resilient(
        () => apiClient.get<AdminUserResponse[]>(`/api/v1/admin/users?q=${encodeURIComponent(query)}`),
        () => {
          const q = query.trim().toLowerCase()
          if (!q) return DEMO_ADMIN_USERS
          return DEMO_ADMIN_USERS.filter((u) =>
            `${u.firstName} ${u.lastName} ${u.phone} ${u.email ?? ''}`.toLowerCase().includes(q),
          )
        },
      ),
  })
}

/** ATTENDU : POST /api/v1/admin/users/{id}/suspend | /reinstate */
export function useToggleUserSuspension() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, suspend }: { id: string; suspend: boolean }) =>
      resilientMutation(
        () => apiClient.post<void>(`/api/v1/admin/users/${id}/${suspend ? 'suspend' : 'reinstate'}`),
        () => undefined,
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'users'] }),
  })
}
