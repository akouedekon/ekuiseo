import { AlertTriangle, BadgeCheck, Gauge, LayoutDashboard, Users, Wallet } from 'lucide-react'
import { NavLink, Outlet } from 'react-router'
import { PageContainer } from '@/components/layout/PageContainer'
import { cn } from '@/lib/cn'

const ADMIN_NAV = [
  { to: '/admin', label: 'Tableau de bord', icon: LayoutDashboard, end: true },
  { to: '/admin/liquidity', label: 'Liquidité', icon: Gauge, end: false },
  { to: '/admin/reports', label: 'Signalements', icon: AlertTriangle, end: false },
  { to: '/admin/verifications', label: 'Vérifications', icon: BadgeCheck, end: false },
  { to: '/admin/payouts', label: 'Reversements', icon: Wallet, end: false },
  { to: '/admin/users', label: 'Utilisateurs', icon: Users, end: false },
]

/**
 * Coque du back-office : navigation laterale au-dela de 1024 px,
 * barre d'onglets defilante en dessous. L'acces reel est controle par l'API
 * (role ADMIN) ; le front n'accorde aucun droit.
 */
export function AdminLayout() {
  return (
    <PageContainer width="lg" className="lg:py-7">
      <div className="mb-5 flex items-baseline justify-between gap-3">
        <div>
          <h1 className="headline text-[28px]">Back-office</h1>
          <p className="mt-0.5 text-[14px] text-muted">Exploitation, modération et paiements</p>
        </div>
      </div>

      <div className="grid gap-6 lg:grid-cols-[212px_minmax(0,1fr)]">
        <nav aria-label="Navigation du back-office" className="lg:sticky lg:top-24 lg:self-start">
          <ul className="scroll-thin flex gap-1 overflow-x-auto pb-1 lg:flex-col lg:overflow-visible lg:pb-0">
            {ADMIN_NAV.map((item) => (
              <li key={item.to} className="shrink-0 lg:w-full">
                <NavLink
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) =>
                    cn(
                      'flex min-h-11 items-center gap-2.5 whitespace-nowrap rounded-[var(--radius-control)] px-3 text-[14px] font-medium transition-colors lg:w-full',
                      isActive
                        ? 'bg-[var(--indigo-soft)] text-[var(--indigo-deep)]'
                        : 'text-ink-2 hover:bg-[var(--surface-calm)] hover:text-ink',
                    )
                  }
                >
                  <item.icon className="size-[18px] shrink-0" aria-hidden />
                  {item.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>

        <div className="min-w-0">
          <Outlet />
        </div>
      </div>
    </PageContainer>
  )
}
