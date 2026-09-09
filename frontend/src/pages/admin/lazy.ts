/*
 * Chargeurs des ecrans du back-office, partages entre le routeur (App.tsx, React.lazy) et
 * la coque AdminLayout, qui les prechauffe des son premier rendu : une fois dans le
 * back-office, passer d un ecran a l autre ne suspend plus jamais le rendu (le chunk est
 * deja la), ce qui evite un ecran vide entre deux pages sur un reseau mobile lent.
 */
export const ADMIN_PAGE_LOADERS = {
  AdminDashboard: () => import('@/pages/admin/AdminDashboard'),
  AdminLiquidity: () => import('@/pages/admin/AdminLiquidity'),
  AdminRetention: () => import('@/pages/admin/AdminRetention'),
  AdminReports: () => import('@/pages/admin/AdminReports'),
  AdminVerifications: () => import('@/pages/admin/AdminVerifications'),
  AdminPayouts: () => import('@/pages/admin/AdminPayouts'),
  AdminPayments: () => import('@/pages/admin/AdminPayments'),
  AdminUsers: () => import('@/pages/admin/AdminUsers'),
  AdminUserDetail: () => import('@/pages/admin/AdminUserDetail'),
  AdminAudit: () => import('@/pages/admin/AdminAudit'),
} as const

let preloaded = false

/** Charge tous les ecrans du back-office en arriere-plan (une fois par session ; jamais bloquant). */
export function preloadAdminPages(): void {
  if (preloaded) return
  preloaded = true
  for (const load of Object.values(ADMIN_PAGE_LOADERS)) {
    // Un echec (hors ligne) est sans consequence : le routeur rechargera le chunk a la demande.
    void load().catch(() => undefined)
  }
}
