import { AnimatePresence, m, useReducedMotion } from 'motion/react'
import { Car, MessageSquare, Plus, Search, Ticket, User } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { toast } from 'sonner'
import { authStore, suspensionStore } from '@/api/client'
import { Button } from '@/components/ui/button'
import { AppTopBar } from '@/components/layout/AppTopBar'
import { AccountMenu, NotificationBell, UnreadPill } from '@/components/layout/HeaderControls'
import { StatusBanners } from '@/components/layout/OfflineBanner'
import { Logo } from '@/components/layout/Logo'
import { PAGE_TITLE_EVENT } from '@/components/layout/PageMeta'
import { PwaInstallBanner } from '@/components/layout/PwaInstallBanner'
import { TermsGate } from '@/features/account/TermsGate'
import { resetSession, useIsAuthenticated, useLogout, useMe } from '@/hooks/useAuth'
import { useIsCompactShell } from '@/hooks/useMediaQuery'
import { useUnreadMessagesCount } from '@/hooks/useMessages'
import { cn } from '@/lib/cn'
import { CONTACT_EMAIL, LEGAL_PAGES } from '@/lib/legal'
import { pageVariants } from '@/lib/motion'
import { isNativeApp } from '@/lib/native'
import { preloadPages } from '@/lib/lazyPage'
import { transitionKeyOf } from '@/lib/navigation'
import { AccountSuspendedPage } from '@/pages/SystemPages'

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
  const navigate = useNavigate()
  const authed = useIsAuthenticated()
  const { data: me } = useMe()
  const logoutLocal = useLogout()
  const logout = () => {
    logoutLocal()
    navigate('/', { replace: true })
  }

  /*
   * Expiration de session detectee par le client HTTP (jeton refuse au
   * rafraichissement) : la session locale est videe comme a une deconnexion
   * (cache memoire, cache persiste, cache du service worker), puis on previent
   * et on renvoie vers la connexion en memorisant l'ecran courant, au lieu de
   * laisser des ecrans en erreur avec les donnees de l'ancien compte.
   */
  useEffect(
    () =>
      authStore.subscribe((authenticated, reason) => {
        if (!authenticated && reason === 'expired') {
          resetSession('expired')
          toast.warning('Votre session a expiré', { description: 'Reconnectez-vous pour continuer.' })
          const next = encodeURIComponent(window.location.pathname + window.location.search)
          navigate(`/login?next=${next}`, { replace: true })
        }
      }),
    [navigate],
  )
  /*
   * Compte suspendu (403 « account-suspended » sur n'importe quel appel) : ecran
   * dedie tant que la session dure, avec le contact du support (audit F257).
   */
  const [suspendedReason, setSuspendedReason] = useState<string | null>(null)
  useEffect(
    () =>
      suspensionStore.subscribe((problem) => {
        setSuspendedReason(problem.detail ?? '')
      }),
    [],
  )
  useEffect(() => {
    if (!authed) setSuspendedReason(null)
  }, [authed])

  const unreadMessages = useUnreadMessagesCount()
  const reduce = useReducedMotion()
  // Coque « application » (barre haute + barre basse) en dessous de 768 px, en-tete web au-dela.
  const compact = useIsCompactShell()

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

  /*
   * Annonce du changement d'ecran (audit F317) : le titre pose par PageMeta est
   * repete dans une zone aria-live, et le focus est place sur le h1 de l'ecran
   * (tabIndex -1) pour que la lecture reprenne au bon endroit. Sans h1, le focus
   * va au conteneur principal.
   */
  const [announcedTitle, setAnnouncedTitle] = useState('')
  const mainRef = useRef<HTMLElement>(null)
  const lastFocusedPath = useRef<string | null>(null)
  useEffect(() => {
    const onTitle = (event: Event) => setAnnouncedTitle((event as CustomEvent<string>).detail)
    window.addEventListener(PAGE_TITLE_EVENT, onTitle)
    return () => window.removeEventListener(PAGE_TITLE_EVENT, onTitle)
  }, [])
  useEffect(() => {
    if (lastFocusedPath.current === null) {
      // Premier rendu : le focus reste ou le navigateur l'a mis.
      lastFocusedPath.current = location.pathname
      return
    }
    if (lastFocusedPath.current === location.pathname) return
    lastFocusedPath.current = location.pathname
    const id = window.setTimeout(() => {
      const target = mainRef.current?.querySelector<HTMLElement>('h1[tabindex="-1"]') ?? mainRef.current
      target?.focus({ preventScroll: true })
    }, 260)
    return () => window.clearTimeout(id)
  }, [location.pathname])

  const user = me
  const isAdmin = user?.role === 'ADMIN'

  // Back-office precharge pour un administrateur : l entree dans /admin ne suspend plus.
  useEffect(() => {
    if (isAdmin) void preloadPages('admin')
  }, [isAdmin])

  // La marge basse de la coque reserve la barre basse (60 px + zone sure) a tous les ecrans : aucun ne finit dessous.
  return (
    <div className="flex min-h-dvh flex-col bg-bg pb-[calc(var(--bottom-nav-h)+12px+env(safe-area-inset-bottom,0px))] md:pb-0">
      <div className="sr-only" aria-live="polite" aria-atomic="true">
        {announcedTitle}
      </div>
      <a
        href="#contenu"
        className="sr-only focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-50 focus:rounded-[var(--radius-control)] focus:bg-primary focus:px-4 focus:py-2 focus:text-on-primary"
      >
        Aller au contenu
      </a>

      {/*
       * Une seule logique de coque : en dessous de 768 px, barre haute d application (titre
       * de l ecran, retour, cloche, compte) et barre basse ; au-dela, l en-tete web avec ses
       * onglets. La coque native (APK) ne change que la zone sure, pas la structure.
       */}
      {compact ? (
        <AppTopBar className="sticky top-0 z-40" />
      ) : (
        <header className="ek-glass app-safe-top sticky top-0 z-40 border-b border-rule">
          <div className="mx-auto flex h-16 max-w-[1200px] items-center gap-3 px-4 sm:px-6">
            <Link to="/" className="shrink-0 rounded-[var(--radius-control)]" aria-label="Ekuiseo, accueil">
              <Logo size={32} />
            </Link>

            {/* Navigation principale : onglets en pilule. */}
            <nav
              className="ml-2 flex flex-1 items-center gap-0.5 rounded-[var(--radius-control)]"
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
                      {/* Pilule statique : les animations de mise en page (layoutId) ne font pas partie de domAnimation. */}
                      {isActive ? (
                        <span aria-hidden className="absolute inset-0 rounded-[var(--radius-control)] bg-primary-soft" />
                      ) : null}
                      <item.icon className="relative size-[18px]" aria-hidden />
                      <span className="relative hidden lg:inline">{item.label}</span>
                      {item.to === '/messages' && authed && unreadMessages > 0 ? (
                        <UnreadPill count={unreadMessages} className="relative -ml-0.5" />
                      ) : null}
                    </>
                  )}
                </NavLink>
              ))}
            </nav>

            <div className="ml-auto flex items-center gap-1.5">
              {authed ? (
                <Button asChild size="sm">
                  <Link to="/publish">
                    <Plus aria-hidden />
                    <span className="hidden lg:inline">Publier un trajet</span>
                    <span className="lg:hidden">Publier</span>
                  </Link>
                </Button>
              ) : null}
              <NotificationBell />
              <AccountMenu />
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
      )}

      <StatusBanners />

      <main id="contenu" ref={mainRef} tabIndex={-1} className="flex-1 pb-6 outline-none md:pb-12">
        {/* Compte suspendu : rien d'autre n'est accessible, la deconnexion reste possible. */}
        {authed && suspendedReason !== null ? (
          <AccountSuspendedPage reason={suspendedReason || undefined} onLogout={logout} />
        ) : authed && user?.termsAcceptanceRequired && !isLegalPath(location.pathname) ? (
          /* CGU a re-accepter : tout l'ecran est bloque, sauf les pages legales elles-memes. */
          <TermsGate />
        ) : (
          <AnimatePresence mode="wait" custom={direction} initial={false}>
            <m.div
              key={transitionKeyOf(location.pathname)}
              custom={direction}
              variants={pageVariants}
              initial="enter"
              animate="center"
              exit="exit"
            >
              <Outlet />
            </m.div>
          </AnimatePresence>
        )}
      </main>

      {/* Dans l application, les textes legaux vivent dans « Compte » et l installation n a pas de sens. */}
      {!isNativeApp() ? <SiteFooter /> : null}
      {!isNativeApp() ? <PwaInstallBanner /> : null}
      {compact ? <BottomNav unreadMessages={authed ? unreadMessages : 0} /> : null}
    </div>
  )
}

/**
 * Ecrans ou l'ecran bloquant des CGU ne s'affiche pas : les pages legales (il faut pouvoir
 * lire ce que l'on accepte) et les ecrans de connexion/inscription. Sur ces derniers, le
 * formulaire doit terminer sa navigation vers la page demandee : si le blocage le
 * remplacait des la validation du code, la page resterait sur /login et l'utilisateur
 * reverrait le formulaire apres avoir accepte. Le blocage apparait donc sur la destination.
 */
function isLegalPath(pathname: string): boolean {
  return pathname === '/login' || pathname === '/register' || LEGAL_PAGES.some((page) => page.path === pathname)
}

/**
 * Pied de page leger (audit F510) : les trois textes legaux et l'adresse de
 * contact, sur toutes les pages. Sur mobile il vit au-dessus de la barre basse (marge de la coque).
 */
function SiteFooter() {
  return (
    <footer className="border-t border-rule bg-bg">
      <div className="mx-auto flex max-w-[1200px] flex-wrap items-center gap-x-4 gap-y-1.5 px-4 py-4 text-caption text-muted sm:px-6">
        <span className="font-semibold text-ink-2">Ekuiseo</span>
        {LEGAL_PAGES.map((page) => (
          <Link key={page.slug} to={page.path} className="underline-offset-4 hover:text-ink hover:underline">
            {page.title}
          </Link>
        ))}
        <a href={`mailto:${CONTACT_EMAIL}`} className="underline-offset-4 hover:text-ink hover:underline">
          {CONTACT_EMAIL}
        </a>
      </div>
    </footer>
  )
}

interface BottomNavItem {
  to: string
  label: string
  icon: typeof Search
  primary?: boolean
  /** Actif : chemin exact (`end`) ou tout chemin verifiant `match`. */
  end?: boolean
  match?: (pathname: string) => boolean
  badge?: number
}

/**
 * Barre basse mobile : quatre destinations et, au centre, l'action « Publier »
 * en bouton plein sureleve. C'est l'action qui cree l'offre : elle merite
 * d'etre la plus visible de l'ecran. « Mes trajets » reunit reservations et
 * trajets conduits (memes onglets) ; « Messages » est la seule porte d'entree
 * mobile du conducteur vers ses passagers, avec le compte de non-lus.
 */
function BottomNav({ unreadMessages }: { unreadMessages: number }) {
  const { pathname } = useLocation()
  const items: BottomNavItem[] = [
    { to: '/', label: 'Rechercher', icon: Search, end: true },
    {
      to: '/bookings',
      label: 'Mes trajets',
      icon: Car,
      match: (path) => (path.startsWith('/bookings') && !/^\/bookings\/[^/]+\/messages/.test(path)) || path.startsWith('/trips/mine'),
    },
    { to: '/publish', label: 'Publier', icon: Plus, primary: true },
    {
      to: '/messages',
      label: 'Messages',
      icon: MessageSquare,
      match: (path) => path.startsWith('/messages') || /^\/bookings\/[^/]+\/messages/.test(path),
      badge: unreadMessages,
    },
    { to: '/me', label: 'Compte', icon: User },
  ]

  return (
    <nav
      className="ek-glass safe-bottom fixed inset-x-0 bottom-0 z-40 border-t border-rule md:hidden"
      aria-label="Navigation principale"
    >
      {/* Hauteur fixe (--bottom-nav-h = 60 px) : la marge basse de la coque et les barres collantes s y calent. */}
      <ul className="mx-auto flex h-[var(--bottom-nav-h)] max-w-lg items-stretch">
        {items.map((item) => {
          const active = item.match ? item.match(pathname) : item.end ? pathname === item.to : pathname.startsWith(item.to)
          const badge = item.badge ?? 0
          return (
            <li key={item.to} className="flex-1">
              {/* Link plutot que NavLink : l'etat actif est calcule ici (un onglet couvre plusieurs routes). */}
              {item.primary ? (
                <Link
                  to={item.to}
                  aria-label={item.label}
                  aria-current={active ? 'page' : undefined}
                  className="flex h-full flex-col items-center justify-end gap-1 pb-1.5"
                >
                  <span
                    className={cn(
                      '-mt-3 flex size-12 items-center justify-center rounded-full text-on-primary shadow-e3 ring-4 ring-bg transition-transform active:scale-95',
                      active ? 'bg-primary-active' : 'bg-primary',
                    )}
                  >
                    <item.icon className="size-6" strokeWidth={2.4} aria-hidden />
                  </span>
                  <span className="text-micro font-semibold leading-none text-primary">{item.label}</span>
                </Link>
              ) : (
                <Link
                  to={item.to}
                  aria-current={active ? 'page' : undefined}
                  aria-label={badge > 0 ? `${item.label}, ${badge} non lu${badge > 1 ? 's' : ''}` : undefined}
                  className={cn(
                    'relative flex h-full flex-col items-center justify-center gap-1 px-1 pt-1.5 pb-1.5 transition-colors active:bg-surface-2',
                    active ? 'text-primary' : 'text-muted',
                  )}
                >
                  <span
                    className={cn(
                      'relative flex h-8 w-14 items-center justify-center rounded-full transition-colors',
                      active && 'bg-primary-soft',
                    )}
                  >
                    {/* Icone 24 px, libelle 11 px, actif en --primary : la grammaire d une barre d onglets native. */}
                    <item.icon className="size-6" strokeWidth={active ? 2.3 : 1.9} aria-hidden />
                    {badge > 0 ? <UnreadPill count={badge} className="absolute -right-1 -top-1.5 ring-2 ring-bg" /> : null}
                  </span>
                  {/* Cran `text-micro` (11 px) : reserve a la barre basse et aux pastilles, conformement a la charte. */}
                  <span className={cn('text-micro leading-none', active ? 'font-semibold' : 'font-medium')}>{item.label}</span>
                </Link>
              )}
            </li>
          )
        })}
      </ul>
    </nav>
  )
}
