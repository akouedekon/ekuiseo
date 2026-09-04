import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { isAuthenticated } from '@/hooks/useAuth'

/** Garde de route : renvoie vers la connexion en mémorisant la destination. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const location = useLocation()
  if (!isAuthenticated()) {
    const next = encodeURIComponent(location.pathname + location.search)
    return <Navigate to={`/login?next=${next}`} replace />
  }
  return <>{children}</>
}
