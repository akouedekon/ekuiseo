import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import {
  Bell,
  Car,
  LayoutDashboard,
  LogOut,
  MessageSquare,
  Monitor,
  Moon,
  Plus,
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

/** Navigation haute (desktop) : l'action « Publier » est un bouton a part, pas un onglet. */
const TOP_NAV = [
  { to: '/', label: 'Rechercher', icon: Search, end: true },
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

  useEffect(() => {
    window.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' })
  }, [location.pathname, reduce])

  const user = me?.data

  return (
    <div className="flex min-h-dvh flex-col bg-bg">
      <a
        href="#contenu"
        className="sr-only focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-50 focus:rounded-[var(--radius-control)] focus:bg-primary focus:px-4 focus:py-2 focus:text-on-primary"
      >
        Aller au contenu
      </a>

      <header className="ek-glass sticky top-0 z-40 border-b border-rule">
        <div className="mx-auto flex h-16 max-w-[1200px] items-center gap-3 px-4 sm:px-6">
          <Link to="/" className="shrink-0 rounded-[var(--radius-control)]" aria-label="Ekuiseo, accueil">
            <Logo size={32} className="[&>span]:hidden sm:[&>span]:flex" />
          </Link>

          {/* Navigation principale : onglets en pilule au-dela de 768 px, barre basse en dessous. */}
          <nav
            className="ml-2 hidden flex-1 items-center gap-0.5 rounded-[var(--radius-control)] md:flex"
            aria-label="Navigation principale"
          >
            {TOP_NAV.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.end}
                aria-label={item.label}
                title={item.label}
                className={({ isActive }) =>
                  cn(
                    'relative flex h-10 items-center gap-2 whitespace-nowrap rounded-[var(--radius-control)] px-3 text-body font-medium transition-colors',
                    isActive ? 'text-primary-ink' : 'text-ink-2 hover:bg-surface-2 hover:text-ink',
                  )
                }
              >
                {({ isActive }) => (
                  <>
                    {isActive ? (
                      <motion.span
                        layoutId="nav-pill"
                        className="absolute inset-0 rounded-[var(--radius-control)] bg-primary-soft"
                        transition={{ type: 'spring', stiffness: 520, damping: 42 }}
                      />
                    ) : null}
                    <item.icon className="relative size-[18px]" aria-hidden />
                    <span className="relative hidden lg:inline">{item.label}</span>
                  </>
                )}
              </NavLink>
            ))}
          </nav>

          <div className="ml-auto flex items-center gap-1.5">
            {authed ? (
              <Button asChild size="sm" className="hidden md:inline-flex">
                <Link to="/publish">
                  <Plus aria-hidden />
                  <span className="hidden lg:inline">Publier un trajet</span>
                  <span className="lg:hidden">Publier</span>
                </Link>
              </Button>
            ) : null}

            {authed ? (
              <Link
                to="/notifications"
                aria-label={unread > 0 ? `Notifications, ${unread} non lues` : 'Notifications'}
                className="relative flex size-10 items-center justify-center rounded-[var(--radius-control)] text-ink-2 transition-colors hover:bg-surface-2 hover:text-ink"
              >
                <Bell className="size-5" aria-hidden />
                {unread > 0 ? (
                  <span className="tnum absolute right-1 top-1 flex min-w-[17px] items-center justify-center rounded-full bg-danger px-1 text-[11px] font-bold leading-[17px] text-on-danger ring-2 ring-bg">
                    {unread > 9 ? '9+' : unread}
                  </span>
                ) : null}
              </Link>
            ) : null}

            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  className="flex size-10 items-center justify-center rounded-full text-ink-2 transition-[box-shadow,color] hover:text-ink data-[state=open]:ring-2 data-[state=open]:ring-primary-soft-2"
                  aria-label={authed && user ? `Menu du compte de ${user.firstName}` : 'Menu du compte'}
                >
                  {authed && user ? (
                    <Avatar firstName={user.firstName} lastName={user.lastName} photoUrl={user.photoUrl} size={34} />
                  ) : (
                    <span className="flex size-9 items-center justify-center rounded-full bg-surface-2">
                      <User className="size-[18px]" aria-hidden />
                    </span>
                  )}
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent className="min-w-60">
                {authed && user ? (
                  <>
                    <div className="flex items-center gap-3 px-2.5 py-2">
                      <Avatar firstName={user.firstName} lastName={user.lastName} photoUrl={user.photoUrl} size={36} />
                      <div className="min-w-0">
                        <p className="truncate text-body font-semibold text-ink">
                          {user.firstName} {user.lastName}
                        </p>
                        <p className="truncate text-caption text-muted">{user.phone}</p>
                      </div>
                    </div>
                    <DropdownMenuSeparator />
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
                {(
                  [
                    { value: 'light', label: 'Clair', icon: Sun },
                    { value: 'dark', label: 'Sombre', icon: Moon },
                    { value: 'system', label: 'Système', icon: Monitor },
                  ] as const
                ).map((option) => (
                  <DropdownMenuItem key={option.value} onSelect={() => setTheme(option.value)}>
                    <option.icon aria-hidden />
                    {option.label}
                    {mode === option.value ? (
                      <span className="ml-auto size-1.5 rounded-full bg-primary" aria-label="Actif" />
                    ) : null}
                  </DropdownMenuItem>
                ))}

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

      <main id="contenu" className="flex-1 pb-28 md:pb-12">
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

/**
 * Barre basse mobile : quatre destinations et, au centre, l'action « Publier »
 * en bouton plein sureleve. C'est l'action qui cree l'offre : elle merite
 * d'etre la plus visible de l'ecran.
 */
function BottomNav() {
  const unread = useUnreadNotificationCount()
  const items = [
    { to: '/', label: 'Rechercher', icon: Search, end: true },
    { to: '/trips/mine', label: 'Mes trajets', icon: Car, end: false },
    { to: '/publish', label: 'Publier', icon: Plus, end: false, primary: true },
    { to: '/bookings', label: 'Réservations', icon: Ticket, end: false },
    { to: '/me', label: 'Compte', icon: User, end: false },
  ]

  return (
    <nav
      className="ek-glass safe-bottom fixed inset-x-0 bottom-0 z-40 border-t border-rule md:hidden"
      aria-label="Navigation principale"
    >
      <ul className="mx-auto flex max-w-lg items-end">
        {items.map((item) => (
          <li key={item.to} className="flex-1">
            {item.primary ? (
              <NavLink
                to={item.to}
                aria-label={item.label}
                className="flex min-h-[60px] flex-col items-center justify-end gap-1 pb-1.5"
              >
                {({ isActive }) => (
                  <>
                    <span
                      className={cn(
                        '-mt-3 flex size-12 items-center justify-center rounded-full text-on-primary shadow-e3 ring-4 ring-bg transition-transform active:scale-95',
                        isActive ? 'bg-primary-active' : 'bg-primary',
                      )}
                    >
                      <item.icon className="size-6" strokeWidth={2.4} aria-hidden />
                    </span>
                    <span className="text-[11px] font-semibold leading-none text-primary-ink">{item.label}</span>
                  </>
                )}
              </NavLink>
            ) : (
              <NavLink
                to={item.to}
                end={item.end}
                className={({ isActive }) =>
                  cn(
                    'relative flex min-h-[60px] flex-col items-center justify-center gap-1 px-1 pt-2 pb-1.5 transition-colors',
                    isActive ? 'text-primary-ink' : 'text-muted',
                  )
                }
              >
                {({ isActive }) => (
                  <>
                    <span
                      className={cn(
                        'relative flex h-7 w-12 items-center justify-center rounded-full transition-colors',
                        isActive && 'bg-primary-soft',
                      )}
                    >
                      <item.icon className="size-[22px]" strokeWidth={isActive ? 2.3 : 1.9} aria-hidden />
                      {item.to === '/me' && unread > 0 ? (
                        <span className="absolute right-2 top-0.5 size-2 rounded-full bg-danger ring-2 ring-bg" />
                      ) : null}
                    </span>
                    {/* 11 px tolere uniquement ici, conformement a la charte. */}
                    <span className="text-[11px] font-medium leading-none">{item.label}</span>
                  </>
                )}
              </NavLink>
            )}
          </li>
        ))}
      </ul>
    </nav>
  )
}
