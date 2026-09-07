import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { useIsAuthenticated, useIsRestoringSession, useMe } from '@/hooks/useAuth'
import { AccessDeniedPage, AppLoadingScreen } from '@/pages/SystemPages'

/**
 * Garde de route : renvoie vers la connexion en memorisant la destination. Reactif a
 * l'expiration de session. Au chargement, tant que la session se restaure depuis le
 * cookie HttpOnly (POST /auth/refresh en cours), l'ecran de chargement s'affiche : on
 * ne renvoie pas vers /login un utilisateur encore connecte.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const location = useLocation()
  const authenticated = useIsAuthenticated()
  const restoring = useIsRestoringSession()
  if (!authenticated && restoring) return <AppLoadingScreen />
  if (!authenticated) {
    const next = encodeURIComponent(location.pathname + location.search)
    return <Navigate to={`/login?next=${next}`} replace />
  }
  return <>{children}</>
}

/**
 * Garde du back-office : le role vient du profil (GET /me). Le serveur reste
 * seul juge (403 sur /api/v1/admin/**) ; ici on evite seulement de charger
 * le module admin et d'afficher un ecran inutile a un compte USER.
 */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const me = useMe()
  if (me.isPending) return <AppLoadingScreen />
  if (me.isError) return <AccessDeniedPage reason="error" onRetry={() => me.refetch()} />
  if (me.data.role !== 'ADMIN') return <AccessDeniedPage reason="forbidden" />
  return <>{children}</>
}
