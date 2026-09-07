import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { apiClient, downloadFile } from '@/api/client'
import type {
  AdminLiquidityResponse,
  AdminOverviewResponse,
  AdminPaymentAccountResponse,
  AdminPaymentResponse,
  AdminPaymentsFilter,
  AdminPayoutResponse,
  AdminReportConversationResponse,
  AdminReportResponse,
  AdminStatsResponse,
  AdminUserBookingResponse,
  AdminUserDetailResponse,
  AdminUserResponse,
  AdminVerificationResponse,
  AuditLogFilters,
  AuditLogResponse,
  FailPayoutRequest,
  IdentityVerificationStatus,
  PayoutBatchResultResponse,
  PayoutResponse,
  ReportResponse,
  ReportStatus,
  SettlePayoutRequest,
} from '@/api/extended'
import type { Page, TripResponse } from '@/api/types'

/*
 * Back-office (/api/v1/admin/**, role ADMIN cote serveur). Le front n'accorde
 * aucun droit par lui-meme : il masque ce que le role ne permet pas et affiche
 * ce que l'API autorise.
 *
 * Cles de cache :
 * - ['admin', 'users', q]            liste / recherche
 * - ['admin', 'user', id, ...]       fiche et sous-listes d'un utilisateur
 * - ['admin', 'overview']            files d'attente (pastilles, tableau de bord)
 * Toute mutation qui vide ou remplit une file invalide aussi 'overview'.
 */

function invalidateUsers(queryClient: QueryClient): void {
  queryClient.invalidateQueries({ queryKey: ['admin', 'users'] })
  queryClient.invalidateQueries({ queryKey: ['admin', 'user'] })
}

function invalidateOverview(queryClient: QueryClient): void {
  queryClient.invalidateQueries({ queryKey: ['admin', 'overview'] })
}

/** Construit une chaine de requete en omettant les valeurs vides. */
function toQuery(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue
    search.set(key, String(value))
  }
  const encoded = search.toString()
  return encoded ? `?${encoded}` : ''
}

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

/**
 * GET /api/v1/admin/overview : files d'attente (signalements, verifications,
 * reversements, remboursements). Rafraichi chaque minute tant que le back-office
 * est ouvert : c'est ce qui alimente les pastilles de navigation.
 */
export function useAdminOverview(enabled = true) {
  return useQuery<AdminOverviewResponse>({
    queryKey: ['admin', 'overview'],
    queryFn: () => apiClient.get<AdminOverviewResponse>('/api/v1/admin/overview'),
    refetchInterval: 60_000,
    staleTime: 30_000,
    enabled,
  })
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

/** PATCH /api/v1/admin/reports/{id} { status } : prise en charge (OPEN -> IN_REVIEW). */
export function useUpdateReportStatus() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: ReportStatus }) =>
      apiClient.patch<AdminReportResponse>(`/api/v1/admin/reports/${id}`, { status }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'reports'] })
      invalidateOverview(queryClient)
    },
  })
}

/** POST /api/v1/admin/reports/{id}/resolve { status, resolutionNote } : cloture motivee (resolu / classe). */
export function useResolveReport() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, status, resolutionNote }: { id: string; status: 'RESOLVED' | 'DISMISSED'; resolutionNote: string }) =>
      apiClient.post<ReportResponse>(`/api/v1/admin/reports/${id}/resolve`, { status, resolutionNote }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'reports'] })
      invalidateOverview(queryClient)
    },
  })
}

/**
 * GET /api/v1/admin/reports/{id}/conversations : echanges lies au dossier, charges a
 * la demande uniquement (l'acces est journalise cote serveur - on ne le declenche pas
 * en ouvrant la liste).
 */
export function useReportConversations(reportId: string | null) {
  return useQuery<AdminReportConversationResponse[]>({
    queryKey: ['admin', 'reports', 'conversations', reportId],
    queryFn: () => apiClient.get<AdminReportConversationResponse[]>(`/api/v1/admin/reports/${reportId}/conversations`),
    enabled: reportId !== null,
    staleTime: 60_000,
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
      invalidateUsers(queryClient)
      invalidateOverview(queryClient)
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

/**
 * POST /api/v1/admin/payouts/{id}/settle { externalReference, settledAmountFcfa? } :
 * marque un lot regle apres le virement mobile money fait hors plateforme. Vaut
 * pour un lot a verser comme pour la relance d'un lot en echec.
 */
export function useSettlePayout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...input }: { id: string } & SettlePayoutRequest) =>
      apiClient.post<PayoutResponse>(`/api/v1/admin/payouts/${id}/settle`, input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'payouts'] })
      invalidateOverview(queryClient)
    },
  })
}

/** POST /api/v1/admin/payouts/{id}/fail { reason } : le virement n'a pas pu etre fait, motif obligatoire. */
export function useFailPayout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...input }: { id: string } & FailPayoutRequest) =>
      apiClient.post<PayoutResponse>(`/api/v1/admin/payouts/${id}/fail`, input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'payouts'] })
      invalidateOverview(queryClient)
    },
  })
}

/** POST /api/v1/admin/payouts/run : constitue les lots de la semaine pour les conducteurs au-dessus du seuil. */
export function useRunPayoutBatch() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiClient.post<PayoutBatchResultResponse>('/api/v1/admin/payouts/run'),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'payouts'] })
      invalidateOverview(queryClient)
    },
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

/** GET /api/v1/admin/users/{id} : fiche complete. */
export function useAdminUserDetail(id: string | undefined) {
  return useQuery<AdminUserDetailResponse>({
    queryKey: ['admin', 'user', id],
    queryFn: () => apiClient.get<AdminUserDetailResponse>(`/api/v1/admin/users/${id}`),
    enabled: Boolean(id),
  })
}

/** GET /api/v1/admin/users/{id}/bookings?page=&size= */
export function useAdminUserBookings(id: string | undefined, page: number, size = 10) {
  return useQuery<Page<AdminUserBookingResponse>>({
    queryKey: ['admin', 'user', id, 'bookings', page, size],
    queryFn: () => apiClient.get<Page<AdminUserBookingResponse>>(`/api/v1/admin/users/${id}/bookings${toQuery({ page, size })}`),
    enabled: Boolean(id),
    placeholderData: (previous) => previous,
  })
}

/** GET /api/v1/admin/users/{id}/trips?page=&size= */
export function useAdminUserTrips(id: string | undefined, page: number, size = 10) {
  return useQuery<Page<TripResponse>>({
    queryKey: ['admin', 'user', id, 'trips', page, size],
    queryFn: () => apiClient.get<Page<TripResponse>>(`/api/v1/admin/users/${id}/trips${toQuery({ page, size })}`),
    enabled: Boolean(id),
    placeholderData: (previous) => previous,
  })
}

/** GET /api/v1/admin/users/{id}/payments?page=&size= */
export function useAdminUserPayments(id: string | undefined, page: number, size = 10) {
  return useQuery<Page<AdminPaymentResponse>>({
    queryKey: ['admin', 'user', id, 'payments', page, size],
    queryFn: () => apiClient.get<Page<AdminPaymentResponse>>(`/api/v1/admin/users/${id}/payments${toQuery({ page, size })}`),
    enabled: Boolean(id),
    placeholderData: (previous) => previous,
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
    onSuccess: () => invalidateUsers(queryClient),
  })
}

/**
 * PATCH /api/v1/admin/users/{id}/contact { email?, phone?, reason } : correction de
 * contact apres verification hors ligne de l'identite. Journalisee, sessions revoquees.
 */
export function useUpdateUserContact() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...input }: { id: string; email?: string; phone?: string; reason: string }) =>
      apiClient.patch<AdminUserResponse>(`/api/v1/admin/users/${id}/contact`, input),
    onSuccess: () => invalidateUsers(queryClient),
  })
}

/**
 * POST /api/v1/admin/users/{id}/anonymize { reason } : droit a l'effacement exerce par
 * l'administration. Profil remplace, contacts effaces, sessions revoquees ; reservations,
 * paiements et avis conserves. Journalise. Refuse (409) si un trajet ou une reservation
 * est en cours.
 */
export function useAnonymizeUser() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      apiClient.post<AdminUserResponse>(`/api/v1/admin/users/${id}/anonymize`, { reason }),
    onSuccess: () => invalidateUsers(queryClient),
  })
}

/** POST /api/v1/admin/users/{id}/revoke-identity { reason } : retire le badge d'identite verifiee, l'utilisateur est prevenu. */
export function useRevokeIdentity() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      apiClient.post<AdminUserResponse>(`/api/v1/admin/users/${id}/revoke-identity`, { reason }),
    onSuccess: () => {
      invalidateUsers(queryClient)
      queryClient.invalidateQueries({ queryKey: ['admin', 'verifications'] })
    },
  })
}

/** POST /api/v1/admin/vehicles/{id}/verify (204) : atteste le vehicule apres controle hors ligne (carte grise, plaque). */
export function useVerifyVehicle() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (vehicleId: string) => apiClient.post<void>(`/api/v1/admin/vehicles/${vehicleId}/verify`),
    onSuccess: () => invalidateUsers(queryClient),
  })
}

/* ------------------------------------------------------------ Paiements (remboursements) */

/** GET /api/v1/admin/payments?status= (TODO = REFUND_PENDING + REFUND_MANUAL, la file de travail). */
export function useAdminPayments(filter: AdminPaymentsFilter) {
  const status = filter === 'TODO' ? '' : `?status=${filter}`
  return useQuery<AdminPaymentResponse[]>({
    queryKey: ['admin', 'payments', filter],
    queryFn: () => apiClient.get<AdminPaymentResponse[]>(`/api/v1/admin/payments${status}`),
    refetchInterval: filter === 'TODO' ? 60_000 : false,
  })
}

/** POST /api/v1/admin/payments/{id}/refund : rejoue l'appel Kkiapay tout de suite. */
export function useRetryRefund() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => apiClient.post<AdminPaymentResponse>(`/api/v1/admin/payments/${id}/refund`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'payments'] })
      invalidateOverview(queryClient)
    },
  })
}

/** POST /api/v1/admin/payments/{id}/mark-refunded { note } */
export function useMarkPaymentRefunded() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, note }: { id: string; note?: string }) =>
      apiClient.post<AdminPaymentResponse>(`/api/v1/admin/payments/${id}/mark-refunded`, { note }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'payments'] })
      invalidateOverview(queryClient)
    },
  })
}

/* ------------------------------------------------------------ Comptes mobile money */

/** GET /api/v1/admin/payment-accounts?verified=false : comptes en attente de vérification de possession. */
export function useAdminPaymentAccounts(verified: boolean) {
  return useQuery<AdminPaymentAccountResponse[]>({
    queryKey: ['admin', 'payment-accounts', verified],
    queryFn: () => apiClient.get<AdminPaymentAccountResponse[]>(`/api/v1/admin/payment-accounts?verified=${verified}`),
  })
}

/** POST /api/v1/admin/payment-accounts/{id}/verify */
export function useVerifyPaymentAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => apiClient.post<AdminPaymentAccountResponse>(`/api/v1/admin/payment-accounts/${id}/verify`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'payment-accounts'] })
      invalidateUsers(queryClient)
    },
  })
}

/* ------------------------------------------------------------ Journal d'audit */

/**
 * GET /api/v1/admin/audit-log?page=&size=&action=&actorId=&entityType=&entityId=&from=&to=
 * Pagine et filtre cote serveur : aucun tri client sur cette table.
 */
export function useAuditLog(page: number, size = 25, filters: AuditLogFilters = {}) {
  return useQuery<Page<AuditLogResponse>>({
    queryKey: ['admin', 'audit-log', page, size, filters],
    queryFn: () => apiClient.get<Page<AuditLogResponse>>(`/api/v1/admin/audit-log${toQuery({ page, size, ...filters })}`),
    placeholderData: (previous) => previous,
  })
}
