import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient, downloadFile } from '@/api/client'
import type {
  AdminLiquidityResponse,
  AdminPayoutResponse,
  AdminReportResponse,
  AdminStatsResponse,
  AdminUserResponse,
  AdminVerificationResponse,
  AuditLogResponse,
  IdentityVerificationStatus,
  PayoutBatchResultResponse,
  PayoutResponse,
  ReportResponse,
  ReportStatus,
} from '@/api/extended'
import type { Page } from '@/api/types'

/*
 * Back-office (/api/v1/admin/**, role ADMIN cote serveur). Le front n'accorde
 * aucun droit par lui-meme : il masque ce que le role ne permet pas et affiche
 * ce que l'API autorise.
 */

/** GET /api/v1/admin/stats?days=N */
export function useAdminStats(days: number, enabled = true) {
  return useQuery<AdminStatsResponse>({
    queryKey: ['admin', 'stats', days],
    queryFn: () => apiClient.get<AdminStatsResponse>(`/api/v1/admin/stats?days=${days}`),
    staleTime: 5 * 60_000,
    enabled,
  })
}

/** GET /api/v1/admin/stats/liquidity?days=N : indicateurs de liquidite et metrique nord. */
export function useAdminLiquidity(days: number, enabled = true) {
  return useQuery<AdminLiquidityResponse>({
    queryKey: ['admin', 'liquidity', days],
    queryFn: () => apiClient.get<AdminLiquidityResponse>(`/api/v1/admin/stats/liquidity?days=${days}`),
    staleTime: 5 * 60_000,
    enabled,
  })
}

/** GET /api/v1/admin/stats/liquidity/export?days=N (CSV). */
export function downloadLiquidityCsv(days: number): Promise<void> {
  return downloadFile(`/api/v1/admin/stats/liquidity/export?days=${days}`, `liquidite-${days}j.csv`)
}

/* ----------------------------------------------------------- Signalements */

/** GET /api/v1/admin/reports[?status=] */
export function useAdminReports(status: ReportStatus | 'ALL') {
  return useQuery<AdminReportResponse[]>({
    queryKey: ['admin', 'reports', status],
    queryFn: () =>
      apiClient.get<AdminReportResponse[]>(`/api/v1/admin/reports${status === 'ALL' ? '' : `?status=${status}`}`),
  })
}

/** PATCH /api/v1/admin/reports/{id} { status } : prise en charge. */
export function useUpdateReportStatus() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: ReportStatus }) =>
      apiClient.patch<AdminReportResponse>(`/api/v1/admin/reports/${id}`, { status }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'reports'] }),
  })
}

/** POST /api/v1/admin/reports/{id}/resolve { status, resolutionNote } : cloture motivee (resolu / classe). */
export function useResolveReport() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, status, resolutionNote }: { id: string; status: 'RESOLVED' | 'DISMISSED'; resolutionNote: string }) =>
      apiClient.post<ReportResponse>(`/api/v1/admin/reports/${id}/resolve`, { status, resolutionNote }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'reports'] }),
  })
}

/* ------------------------------------------------------- Verifications */

/** GET /api/v1/admin/verifications?status= (PENDING par defaut). */
export function useAdminVerifications(status: IdentityVerificationStatus = 'PENDING') {
  return useQuery<AdminVerificationResponse[]>({
    queryKey: ['admin', 'verifications', status],
    queryFn: () => apiClient.get<AdminVerificationResponse[]>(`/api/v1/admin/verifications?status=${status}`),
  })
}

/** POST /api/v1/admin/verifications/{id}/approve | /reject { reason } */
export function useReviewVerification() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, approve, reason }: { id: string; approve: boolean; reason?: string }) =>
      apiClient.post<void>(
        `/api/v1/admin/verifications/${id}/${approve ? 'approve' : 'reject'}`,
        approve ? undefined : { reason },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'verifications'] })
      queryClient.invalidateQueries({ queryKey: ['admin', 'users'] })
    },
  })
}

/* ------------------------------------------------------------ Reversements */

/** GET /api/v1/admin/payouts */
export function useAdminPayouts() {
  return useQuery<AdminPayoutResponse[]>({
    queryKey: ['admin', 'payouts'],
    queryFn: () => apiClient.get<AdminPayoutResponse[]>('/api/v1/admin/payouts'),
  })
}

/** POST /api/v1/admin/payouts/{id}/pay : marque un lot comme verse (decaissement fait hors plateforme). */
export function useMarkPayoutPaid() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => apiClient.post<PayoutResponse>(`/api/v1/admin/payouts/${id}/pay`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'payouts'] }),
  })
}

/** POST /api/v1/admin/payouts/run : constitue les lots de la semaine pour les conducteurs au-dessus du seuil. */
export function useRunPayoutBatch() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiClient.post<PayoutBatchResultResponse>('/api/v1/admin/payouts/run'),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'payouts'] }),
  })
}

/* ------------------------------------------------------------ Utilisateurs */

/** GET /api/v1/admin/users?q= */
export function useAdminUsers(query: string) {
  return useQuery<AdminUserResponse[]>({
    queryKey: ['admin', 'users', query],
    queryFn: () => apiClient.get<AdminUserResponse[]>(`/api/v1/admin/users?q=${encodeURIComponent(query)}`),
  })
}

/** POST /api/v1/admin/users/{id}/suspend { reason } | /reinstate */
export function useToggleUserSuspension() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, suspend, reason }: { id: string; suspend: boolean; reason?: string }) =>
      suspend
        ? apiClient.post<AdminUserResponse>(`/api/v1/admin/users/${id}/suspend`, { reason })
        : apiClient.post<AdminUserResponse>(`/api/v1/admin/users/${id}/reinstate`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'users'] }),
  })
}

/* ------------------------------------------------------------ Journal d'audit */

/** GET /api/v1/admin/audit-log?page=&size= */
export function useAuditLog(page: number, size = 25) {
  return useQuery<Page<AuditLogResponse>>({
    queryKey: ['admin', 'audit-log', page, size],
    queryFn: () => apiClient.get<Page<AuditLogResponse>>(`/api/v1/admin/audit-log?page=${page}&size=${size}`),
    placeholderData: (previous) => previous,
  })
}
