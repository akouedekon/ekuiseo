import {
  AlertTriangle,
  BadgeCheck,
  Gauge,
  LayoutDashboard,
  PanelLeftClose,
  PanelLeftOpen,
  ShieldOff,
  Users,
  Wallet,
  type LucideIcon,
} from 'lucide-react'
import { useState, type CSSProperties } from 'react'
import { Link, NavLink, Outlet } from 'react-router'
import { ApiError } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Tooltip } from '@/components/ui/misc'
import { Logo } from '@/components/layout/Logo'
import { PageContainer } from '@/components/layout/PageContainer'
import { useAdminStats } from '@/hooks/useAdmin'
import { cn } from '@/lib/cn'

interface AdminNavItem {
  to: string
  label: string
  icon: LucideIcon
  end: boolean
}

interface AdminNavGroup {
  label: string
  items: AdminNavItem[]
}

const ADMIN_NAV: AdminNavGroup[] = [
  {
    label: 'Pilotage',
    items: [
      { to: '/admin', label: 'Tableau de bord', icon: LayoutDashboard, end: true },
      { to: '/admin/liquidity', label: 'Liquidité', icon: Gauge, end: false },
    ],
  },
  {
    label: 'Opérations',
    items: [
      { to: '/admin/reports', label: 'Signalements', icon: AlertTriangle, end: false },
      { to: '/admin/verifications', label: 'Vérifications', icon: BadgeCheck, end: false },
      { to: '/admin/payouts', label: 'Reversements', icon: Wallet, end: false },
      { to: '/admin/users', label: 'Utilisateurs', icon: Users, end: false },
    ],
  },
]

const SIDEBAR_STORAGE_KEY = 'ekuiseo.admin.sidebar'
const SIDEBAR_WIDTH = '232px'
const SIDEBAR_WIDTH_COLLAPSED = '64px'

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(SIDEBAR_STORAGE_KEY) === 'collapsed'
  } catch {
    return false
  }
}

function storeCollapsed(collapsed: boolean): void {
  try {
    localStorage.setItem(SIDEBAR_STORAGE_KEY, collapsed ? 'collapsed' : 'open')
  } catch {
    /* stockage indisponible : le reglage vaut pour la session */
  }
}

/**
 * Coque du back-office.
 * - Au-dela de 1024 px : panneau lateral avec bloc de marque et navigation
 *   groupee, reductible en rail d'icones (choix memorise), collant au defilement.
 * - En dessous : barre d'onglets horizontale defilante, sans tiroir : les six
 *   entrees restent accessibles d'un geste.
 * L'acces reel est controle par l'API (role ADMIN) : un 403 est traite comme
 * un ecran a part entiere, jamais comme une erreur de chargement.
 */
export function AdminLayout() {
  const [collapsed, setCollapsed] = useState(readCollapsed)
  const access = useAdminStats(7)
  const forbidden = access.isError && access.error instanceof ApiError && access.error.status === 403

  const toggle = () => {
    setCollapsed((current) => {
      storeCollapsed(!current)
      return !current
    })
  }

  if (forbidden) return <AccessDenied />

  return (
    <PageContainer width="lg">
      <div
        className="grid gap-6 transition-[grid-template-columns] duration-200 ease-out lg:grid-cols-[var(--admin-sidebar)_minmax(0,1fr)] lg:gap-8"
        style={{ '--admin-sidebar': collapsed ? SIDEBAR_WIDTH_COLLAPSED : SIDEBAR_WIDTH } as CSSProperties}
      >
        <aside className="min-w-0 lg:sticky lg:top-24 lg:self-start">
          {/* Bloc de marque : ou suis-je, en un coup d'oeil. */}
          <div
            className={cn(
              'mb-3 hidden items-center gap-3 rounded-[var(--radius-card)] border border-rule bg-surface p-3 shadow-e1 lg:flex',
              collapsed && 'justify-center p-2',
            )}
          >
            <Logo size={32} variant="mark" />
            {!collapsed ? (
              <div className="min-w-0">
                <p className="truncate font-display text-body font-bold leading-tight text-ink">Back-office</p>
                <p className="truncate text-caption text-muted">Ekuiseo · pilotage</p>
              </div>
            ) : null}
          </div>

          <nav aria-label="Navigation du back-office">
            <ul className="scroll-thin flex gap-1 overflow-x-auto pb-1 lg:flex-col lg:gap-0 lg:overflow-visible lg:pb-0">
              {ADMIN_NAV.map((group, groupIndex) => (
                <li key={group.label} className="contents lg:block">
                  {!collapsed ? (
                    <p
                      className={cn(
                        'hidden px-3 text-caption font-semibold uppercase tracking-[0.08em] text-muted lg:block',
                        groupIndex === 0 ? 'mb-1.5' : 'mb-1.5 mt-4',
                      )}
                    >
                      {group.label}
                    </p>
                  ) : groupIndex > 0 ? (
                    <span aria-hidden className="mx-auto my-2 hidden h-px w-6 bg-rule lg:block" />
                  ) : null}
                  <ul className="contents lg:flex lg:flex-col lg:gap-0.5">
                    {group.items.map((item) => (
                      <li key={item.to} className="shrink-0 lg:w-full">
                        <AdminNavLink item={item} collapsed={collapsed} />
                      </li>
                    ))}
                  </ul>
                </li>
              ))}
            </ul>
          </nav>

          <div className="mt-3 hidden border-t border-rule pt-3 lg:block">
            <Tooltip label={collapsed ? 'Déployer la navigation' : 'Réduire la navigation'}>
              <Button
                variant="ghost"
                size={collapsed ? 'icon' : 'sm'}
                onClick={toggle}
                aria-expanded={!collapsed}
                aria-label={collapsed ? 'Déployer la navigation' : 'Réduire la navigation'}
                className={cn('text-muted', !collapsed && 'w-full justify-start px-3')}
              >
                {collapsed ? (
                  <PanelLeftOpen aria-hidden />
                ) : (
                  <>
                    <PanelLeftClose aria-hidden />
                    Réduire
                  </>
                )}
              </Button>
            </Tooltip>
          </div>
        </aside>

        <div className="min-w-0">
          <Outlet />
        </div>
      </div>
    </PageContainer>
  )
}

function AdminNavLink({ item, collapsed }: { item: AdminNavItem; collapsed: boolean }) {
  const link = (
    <NavLink
      to={item.to}
      end={item.end}
      aria-label={collapsed ? item.label : undefined}
      className={({ isActive }) =>
        cn(
          'flex min-h-10 items-center gap-2.5 whitespace-nowrap rounded-[var(--radius-control)] px-3 text-body font-medium transition-colors lg:w-full',
          collapsed && 'lg:justify-center lg:px-0',
          isActive
            ? 'bg-primary-soft text-primary-ink shadow-[inset_0_0_0_1px_var(--primary-soft-2)]'
            : 'text-ink-2 hover:bg-surface-2 hover:text-ink',
        )
      }
    >
      <item.icon className="size-[18px] shrink-0" aria-hidden />
      <span className={cn(collapsed && 'lg:sr-only')}>{item.label}</span>
    </NavLink>
  )

  return collapsed ? (
    <>
      <span className="hidden lg:block">
        <Tooltip label={item.label}>{link}</Tooltip>
      </span>
      <span className="lg:hidden">{link}</span>
    </>
  ) : (
    link
  )
}

/** 403 sur la premiere requete admin : le compte n'a pas le role. */
function AccessDenied() {
  return (
    <PageContainer width="sm" className="flex min-h-[calc(100dvh-10rem)] flex-col items-center justify-center text-center">
      <span className="flex size-14 items-center justify-center rounded-[var(--radius-card)] bg-danger-soft text-danger-ink shadow-e1">
        <ShieldOff className="size-6" aria-hidden />
      </span>
      <h1 className="headline mt-4 text-[26px]">Accès réservé</h1>
      <p className="mt-2 max-w-sm text-base leading-relaxed text-muted">
        Le back-office est réservé à l'équipe Ekuiseo. Votre compte n'a pas les droits nécessaires.
      </p>
      <div className="mt-6 flex w-full max-w-xs flex-col gap-2">
        <Button asChild size="lg" block>
          <Link to="/">Retour à l'accueil</Link>
        </Button>
        <Button asChild variant="ghost" block>
          <Link to="/me">Mon compte</Link>
        </Button>
      </div>
    </PageContainer>
  )
}
