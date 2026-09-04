import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import {
  Bell,
  Car,
  LayoutDashboard,
  LogOut,
  MessageSquare,
  Monitor,
  Moon,
  PlusCircle,
  Search,
  Sun,
  Ticket,
  User,
} from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, NavLink, Outlet, useLocation } from 'react-router'
import { Avatar } from '@/components/ui/misc'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { StatusBanners } from '@/components/layout/OfflineBanner'
import { Logo } from '@/components/layout/Logo'
import { isAuthenticated, useLogout, useMe } from '@/hooks/useAuth'
import { useUnreadNotificationCount } from '@/hooks/useNotifications'
import { useTheme } from '@/hooks/useTheme'
import { cn } from '@/lib/cn'
import { pageVariants } from '@/lib/motion'

/** Profondeur de navigation : sert a donner sa direction a la transition. */
const DEPTH: { match: RegExp; depth: number }[] = [
  { match: /^\/$/, depth: 0 },
  { match: /^\/search/, depth: 1 },
  { match: /^\/trips\/[^/]+$/, depth: 2 },
  { match: /^\/book\//, depth: 3 },
  { match: /^\/bookings\/[^/]+\/messages/, depth: 3 },
  { match: /^\/bookings\/[^/]+/, depth: 2 },
  { match: /^\/drivers\//, depth: 3 },
]

function depthOf(pathname: string): number {
  return DEPTH.find((entry) => entry.match.test(pathname))?.depth ?? 1
}

const NAV_ITEMS = [
  { to: '/', label: 'Rechercher', icon: Search, end: true },
  { to: '/publish', label: 'Publier', icon: PlusCircle, end: false },
  { to: '/trips/mine', label: 'Mes trajets', icon: Car, end: false },
  { to: '/bookings', label: 'Réservations', icon: Ticket, end: false },
  { to: '/messages', label: 'Messages', icon: MessageSquare, end: false },
]

export function AppShell() {
  const location = useLocation()
  const authed = isAuthenticated()
  const { data: me } = useMe()
  const logout = useLogout()
  const unread = useUnreadNotificationCount()
  const { mode, setTheme } = useTheme()
  const reduce = useReducedMotion()

  /*
   * Direction de la transition : on compare la profondeur de l'ecran quitte a
   * celle de l'ecran demande. L'ajustement de l'etat pendant le rendu est le
   * schema recommande par React pour deriver d'une prop qui change.
   */
  const [previousPath, setPreviousPath] = useState(location.pathname)
  const [rawDirection, setRawDirection] = useState(0)
  if (previousPath !== location.pathname) {
    setPreviousPath(location.pathname)
    setRawDirection(Math.sign(depthOf(location.pathname) - depthOf(previousPath)) || 0)
  }
  const direction = reduce ? 0 : rawDirection

  // Remontee en haut a chaque changement d'ecran (sinon on garde le scroll precedent).
  useEffect(() => {
    window.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' })
  }, [location.pathname, reduce])

  const user = me?.data

  return (
    <div className="flex min-h-dvh flex-col bg-paper">
      <a
        href="#contenu"
        className="sr-only focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-50 focus:rounded-[var(--radius-control)] focus:bg-[var(--indigo)] focus:px-4 focus:py-2 focus:text-[var(--indigo-contrast)]"
      >
        Aller au contenu
      </a>

      <header className="sticky top-0 z-40 border-b border-rule bg-[color-mix(in_srgb,var(--paper)_88%,transparent)] backdrop-blur-md">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-4 px-4">
          <Link to="/" className="shrink-0" aria-label="Ekuiseo, accueil">
            <Logo />
          </Link>

          {/* Navigation principale : barre haute au-dela de 768 px, barre basse en dessous. */}
          <nav className="hidden flex-1 items-center gap-1 md:flex" aria-label="Navigation principale">
            {NAV_ITEMS.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.end}
                className={({ isActive }) =>
                  cn(
                    'relative flex h-11 items-center gap-2 rounded-[var(--radius-control)] px-3 text-[14px] font-medium transition-colors',
                    isActive ? 'text-[var(--indigo)]' : 'text-ink-2 hover:bg-[var(--surface-calm)] hover:text-ink',
                  )
                }
              >
                {({ isActive }) => (
                  <>
                    <item.icon className="size-[18px]" aria-hidden />
                    {item.label}
                    {isActive ? (
                      <motion.span
                        layoutId="nav-underline"
                        className="absolute inset-x-2 -bottom-[9px] h-0.5 rounded-full bg-[var(--indigo)]"
                        transition={{ type: 'spring', stiffness: 520, damping: 40 }}
                      />
                    ) : null}
                  </>
                )}
              </NavLink>
            ))}
          </nav>

          <div className="ml-auto flex items-center gap-1">
            {authed ? (
              <Link
                to="/notifications"
                aria-label={unread > 0 ? `Notifications, ${unread} non lues` : 'Notifications'}
                className="relative flex size-11 items-center justify-center rounded-[var(--radius-control)] text-ink-2 transition-colors hover:bg-[var(--surface-calm)] hover:text-ink"
              >
                <Bell className="size-[20px]" aria-hidden />
                {unread > 0 ? (
                  <span className="tnum absolute right-1.5 top-1.5 flex min-w-[17px] items-center justify-center rounded-full bg-[var(--vermillon)] px-1 text-[11px] font-bold leading-[17px] text-[var(--vermillon-contrast)]">
                    {unread > 9 ? '9+' : unread}
                  </span>
                ) : null}
              </Link>
            ) : null}

            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  className="flex size-11 items-center justify-center rounded-[var(--radius-control)] text-ink-2 transition-colors hover:bg-[var(--surface-calm)] hover:text-ink"
                  aria-label={
                    authed && user ? `Menu du compte de ${user.firstName}` : 'Menu du compte'
                  }
                >
                  {authed && user ? (
                    <Avatar firstName={user.firstName} lastName={user.lastName} photoUrl={user.photoUrl} size={32} />
                  ) : (
                    <User className="size-[20px]" aria-hidden />
                  )}
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent>
                {authed && user ? (
                  <>
                    <DropdownMenuLabel>
                      {user.firstName} {user.lastName}
                    </DropdownMenuLabel>
                    <DropdownMenuItem asChild>
                      <Link to="/me">
                        <User aria-hidden />
                        Mon compte
                      </Link>
                    </DropdownMenuItem>
                    <DropdownMenuItem asChild>
                      <Link to="/admin">
                        <LayoutDashboard aria-hidden />
                        Back-office
                      </Link>
                    </DropdownMenuItem>
                    <DropdownMenuSeparator />
                  </>
                ) : null}

                <DropdownMenuLabel>Apparence</DropdownMenuLabel>
                <DropdownMenuItem onSelect={() => setTheme('light')}>
                  <Sun aria-hidden />
                  Clair
                  {mode === 'light' ? <span className="ml-auto text-[12px] text-muted">Actif</span> : null}
                </DropdownMenuItem>
                <DropdownMenuItem onSelect={() => setTheme('dark')}>
                  <Moon aria-hidden />
                  Sombre
                  {mode === 'dark' ? <span className="ml-auto text-[12px] text-muted">Actif</span> : null}
                </DropdownMenuItem>
                <DropdownMenuItem onSelect={() => setTheme('system')}>
                  <Monitor aria-hidden />
                  Système
                  {mode === 'system' ? <span className="ml-auto text-[12px] text-muted">Actif</span> : null}
                </DropdownMenuItem>

                {authed ? (
                  <>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem tone="danger" onSelect={logout}>
                      <LogOut aria-hidden />
                      Déconnexion
                    </DropdownMenuItem>
                  </>
                ) : null}
              </DropdownMenuContent>
            </DropdownMenu>

            {!authed ? (
              <Button asChild size="sm" className="ml-1">
                <Link to="/login">Connexion</Link>
              </Button>
            ) : null}
          </div>
        </div>
        {/* Filet tricolore : signature graphique, 3 px, jamais decoratif ailleurs. */}
        <div aria-hidden className="banner-rule h-[3px]" />
      </header>

      <StatusBanners />

      <main id="contenu" className="flex-1 pb-24 md:pb-10">
        <AnimatePresence mode="wait" custom={direction} initial={false}>
          <motion.div
            key={location.pathname}
            custom={direction}
            variants={pageVariants}
            initial="enter"
            animate="center"
            exit="exit"
          >
            <Outlet />
          </motion.div>
        </AnimatePresence>
      </main>

      <BottomNav />
    </div>
  )
}

function BottomNav() {
  const unread = useUnreadNotificationCount()
  const items = [
    ...NAV_ITEMS.slice(0, 4),
    { to: '/me', label: 'Compte', icon: User, end: false },
  ]

  return (
    <nav
      className="safe-bottom fixed inset-x-0 bottom-0 z-40 border-t border-rule bg-[color-mix(in_srgb,var(--surface)_94%,transparent)] backdrop-blur-md md:hidden"
      aria-label="Navigation principale"
    >
      <ul className="mx-auto flex max-w-lg">
        {items.map((item) => (
          <li key={item.to} className="flex-1">
            <NavLink
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                cn(
                  'relative flex min-h-[56px] flex-col items-center justify-center gap-0.5 px-1 py-1.5 transition-colors',
                  isActive ? 'text-[var(--indigo)]' : 'text-muted',
                )
              }
            >
              {({ isActive }) => (
                <>
                  {isActive ? (
                    <motion.span
                      layoutId="bottomnav-marker"
                      className="absolute inset-x-4 top-0 h-0.5 rounded-full bg-[var(--indigo)]"
                      transition={{ type: 'spring', stiffness: 520, damping: 40 }}
                    />
                  ) : null}
                  <span className="relative">
                    <item.icon className="size-[22px]" strokeWidth={isActive ? 2.3 : 1.9} aria-hidden />
                    {item.to === '/me' && unread > 0 ? (
                      <span className="absolute -right-1 -top-0.5 size-2 rounded-full bg-[var(--vermillon)]" />
                    ) : null}
                  </span>
                  {/* 11 px tolere uniquement ici, conformement a la charte. */}
                  <span className="text-[11px] font-medium leading-none">{item.label}</span>
                </>
              )}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}
