import { ChevronLeft } from 'lucide-react'
import { Link, useLocation, useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { AccountMenu, NotificationBell } from '@/components/layout/HeaderControls'
import { Logo } from '@/components/layout/Logo'
import { useScreen } from '@/components/layout/screenStore'
import { useIsAuthenticated } from '@/hooks/useAuth'
import { LEGAL_PAGES } from '@/lib/legal'

/*
 * Barre haute d application (en dessous de 768 px, navigateur comme APK) : 56 px, zone
 * sure en haut, titre de l ecran au centre, retour a gauche sur les ecrans secondaires,
 * cloche et compte a droite. Sur l accueil, le logo remplace le titre. Le bouton retour
 * inline des ecrans (PageHeader) disparait a cette taille : c est elle qui le porte.
 */

/** Ecrans racines : ceux de la barre basse et la connexion. Pas de retour, on y arrive par un onglet. */
const ROOT_PATHS = new Set(['/', '/bookings', '/trips/mine', '/publish', '/messages', '/me', '/autour', '/login'])

/** Titre par ecran ; a defaut, le titre court pose par PageMeta. */
const TITLES: { match: RegExp; title: string }[] = [
  { match: /^\/search/, title: 'Résultats' },
  { match: /^\/trips\/mine$/, title: 'Mes trajets' },
  { match: /^\/trips\/[^/]+$/, title: 'Trajet' },
  { match: /^\/drivers\//, title: 'Profil' },
  { match: /^\/login$/, title: 'Connexion' },
  { match: /^\/register$/, title: 'Inscription' },
  { match: /^\/live\//, title: 'Suivi en direct' },
  { match: /^\/autour$/, title: 'Autour de moi' },
  { match: /^\/book\//, title: 'Réservation' },
  { match: /^\/publish$/, title: 'Publier un trajet' },
  { match: /^\/bookings\/[^/]+\/messages/, title: 'Conversation' },
  { match: /^\/bookings$/, title: 'Mes trajets' },
  { match: /^\/messages$/, title: 'Messages' },
  { match: /^\/me$/, title: 'Mon compte' },
  { match: /^\/notifications$/, title: 'Notifications' },
  { match: /^\/admin/, title: 'Back-office' },
  ...LEGAL_PAGES.map((page) => ({ match: new RegExp(`^${page.path}$`), title: page.title })),
]

function titleForPath(pathname: string, fallback: string | null): string {
  return TITLES.find((entry) => entry.match.test(pathname))?.title ?? fallback ?? 'Ekuiseo'
}

export function AppTopBar({ className }: { className?: string }) {
  const location = useLocation()
  const navigate = useNavigate()
  const authed = useIsAuthenticated()
  const screen = useScreen()
  const pathname = location.pathname.replace(/\/$/, '') || '/'
  const home = pathname === '/'
  const secondary = !ROOT_PATHS.has(pathname)
  // react-router marque la toute premiere entree de session par la cle « default ».
  const canGoBack = location.key !== 'default'
  const goBack = () => (canGoBack ? navigate(-1) : navigate(screen.backTo ?? '/', { replace: true }))
  const onLoginScreen = pathname === '/login' || pathname === '/register'

  return (
    <header className={className}>
      <div className="app-safe-top ek-glass border-b border-rule">
        <div className="grid h-14 grid-cols-[minmax(88px,1fr)_auto_minmax(88px,1fr)] items-center px-2">
          <div className="flex items-center justify-start">
            {secondary ? (
              <button
                type="button"
                onClick={goBack}
                aria-label="Revenir à l'écran précédent"
                className="flex size-11 items-center justify-center rounded-[var(--radius-control)] text-ink transition-colors hover:bg-surface-2 active:bg-surface-2"
              >
                <ChevronLeft className="size-6" aria-hidden />
              </button>
            ) : (
              <Link to="/" className="ml-1 flex h-11 items-center rounded-[var(--radius-control)]" aria-label="Ekuiseo, accueil">
                <Logo size={30} variant={home ? 'full' : 'mark'} />
              </Link>
            )}
          </div>

          <div className="min-w-0 px-1 text-center">
            {home ? null : (
              <p className="truncate font-display text-title font-bold tracking-[-0.02em] text-ink" aria-hidden>
                {titleForPath(pathname, screen.title)}
              </p>
            )}
          </div>

          <div className="flex items-center justify-end gap-0.5">
            <NotificationBell />
            <AccountMenu />
            {!authed && !onLoginScreen ? (
              <Button asChild size="sm" className="ml-1">
                <Link to="/login">Connexion</Link>
              </Button>
            ) : null}
          </div>
        </div>
      </div>
      {/* Filet tricolore : signature graphique, 3 px, jamais decoratif ailleurs. */}
      <div aria-hidden className="banner-rule h-[3px]" />
    </header>
  )
}
