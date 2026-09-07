import { m } from 'motion/react'
import { Ban, Compass, Home, LifeBuoy, RotateCcw, ShieldOff } from 'lucide-react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { PageContainer } from '@/components/layout/PageContainer'
import { Logo } from '@/components/layout/Logo'
import { PageMeta } from '@/components/layout/PageMeta'
import { CONTACT_EMAIL } from '@/lib/legal'

/** Gabarit commun aux ecrans systeme : centre, sobre, une seule action claire. */
function SystemScreen({
  code,
  title,
  description,
  children,
}: {
  code?: string
  title: string
  description: string
  children: React.ReactNode
}) {
  return (
    <PageContainer width="sm" className="flex min-h-[calc(100dvh-10rem)] flex-col items-center justify-center text-center">
      <PageMeta title={title} noindex />
      {code ? (
        <m.p
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          className="headline text-[72px] leading-none text-primary-ink"
        >
          {code}
        </m.p>
      ) : null}
      <m.h1
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.05 }}
        tabIndex={-1}
        className="headline mt-3 text-display outline-none"
      >
        {title}
      </m.h1>
      <m.p
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 0.1 }}
        className="mt-2 max-w-sm text-base leading-relaxed text-muted"
      >
        {description}
      </m.p>
      <div className="mt-6 flex w-full max-w-xs flex-col gap-2">{children}</div>
    </PageContainer>
  )
}

/** 404 — route inexistante. */
export function NotFoundPage() {
  return (
    <SystemScreen
      code="404"
      title="Page introuvable"
      description="Cette adresse ne correspond à aucun écran d'Ekuiseo. Elle a peut-être changé."
    >
      <Button asChild size="lg" block>
        <Link to="/">
          <Home className="size-4" aria-hidden />
          Retour à l'accueil
        </Link>
      </Button>
      <Button asChild variant="ghost" block>
        <Link to="/bookings">
          <Compass className="size-4" aria-hidden />
          Voir mes réservations
        </Link>
      </Button>
    </SystemScreen>
  )
}

/**
 * Compte suspendu par la moderation (403 « account-suspended », audit F257) :
 * un ecran qui dit ce qui se passe et comment contester, au lieu d'une
 * redirection vers la connexion qui laisserait croire a une session expiree.
 */
export function AccountSuspendedPage({ reason, onLogout }: { reason?: string; onLogout: () => void }) {
  const mailto = `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent('Contestation de suspension de compte')}`
  return (
    <PageContainer width="sm" className="flex min-h-[calc(100dvh-10rem)] flex-col items-center justify-center text-center">
      <PageMeta title="Compte suspendu" noindex />
      <span className="flex size-14 items-center justify-center rounded-[var(--radius-card)] bg-danger-soft text-danger-ink shadow-e1">
        <Ban className="size-6" aria-hidden />
      </span>
      <h1 tabIndex={-1} className="headline mt-4 text-display outline-none">
        Compte suspendu
      </h1>
      <p className="mt-2 max-w-sm text-base leading-relaxed text-muted">
        {reason
          ? `Votre compte a été suspendu par l'équipe Ekuiseo : ${reason}`
          : "Votre compte a été suspendu par l'équipe Ekuiseo."}{' '}
        Vous ne pouvez plus réserver ni publier tant que la décision n'est pas levée.
      </p>
      <div className="mt-6 flex w-full max-w-xs flex-col gap-2">
        <Button asChild size="lg" block>
          <a href={mailto}>
            <LifeBuoy className="size-4" aria-hidden />
            Contester ({CONTACT_EMAIL})
          </a>
        </Button>
        <Button variant="ghost" block onClick={onLogout}>
          Se déconnecter
        </Button>
      </div>
    </PageContainer>
  )
}

/** Acces refuse (role insuffisant) ou profil impossible a charger avant la verification du role. */
export function AccessDeniedPage({ reason, onRetry }: { reason: 'forbidden' | 'error'; onRetry?: () => void }) {
  return (
    <PageContainer width="sm" className="flex min-h-[calc(100dvh-10rem)] flex-col items-center justify-center text-center">
      <PageMeta title={reason === 'forbidden' ? 'Accès réservé' : 'Vérification impossible'} noindex />
      <span className="flex size-14 items-center justify-center rounded-[var(--radius-card)] bg-danger-soft text-danger-ink shadow-e1">
        <ShieldOff className="size-6" aria-hidden />
      </span>
      <h1 tabIndex={-1} className="headline mt-4 text-display outline-none">
        {reason === 'forbidden' ? 'Accès réservé' : 'Vérification impossible'}
      </h1>
      <p className="mt-2 max-w-sm text-base leading-relaxed text-muted">
        {reason === 'forbidden'
          ? "Le back-office est réservé à l'équipe Ekuiseo. Votre compte n'a pas les droits nécessaires."
          : "Impossible de vérifier vos droits pour l'instant. Vérifiez votre connexion, puis réessayez."}
      </p>
      <div className="mt-6 flex w-full max-w-xs flex-col gap-2">
        {reason === 'error' && onRetry ? (
          <Button size="lg" block onClick={onRetry}>
            <RotateCcw className="size-4" aria-hidden />
            Réessayer
          </Button>
        ) : null}
        <Button asChild size="lg" block variant={reason === 'error' ? 'ghost' : 'primary'}>
          <Link to="/">Retour à l'accueil</Link>
        </Button>
        <Button asChild variant="ghost" block>
          <Link to="/me">Mon compte</Link>
        </Button>
      </div>
    </PageContainer>
  )
}

/**
 * Ecran d'erreur applicative, rendu par la frontiere d'erreur. Le detail
 * technique reste dans la console (voir ErrorBoundary) : l'utilisateur n'a
 * rien a en faire, et il pourrait contenir des informations internes.
 */
export function ErrorPage({ onReset }: { onReset?: () => void }) {
  return (
    <SystemScreen
      title="Un problème est survenu"
      description="L'application a rencontré une erreur inattendue. Rien n'a été perdu : vos données sont conservées."
    >
      <Button size="lg" block onClick={() => (onReset ? onReset() : window.location.reload())}>
        <RotateCcw className="size-4" aria-hidden />
        Recharger l'application
      </Button>
      <Button asChild variant="ghost" block>
        <Link to="/">Retour à l'accueil</Link>
      </Button>
    </SystemScreen>
  )
}

/**
 * Ecran de chargement initial (avant hydratation du cache et des polices). La
 * barre qui balaie est en CSS pur (`.shimmer`), donc neutralisee par
 * prefers-reduced-motion comme le reste (audit F316).
 */
export function AppLoadingScreen() {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-4 bg-bg" role="status">
      <m.div
        initial={{ opacity: 0, scale: 0.94 }}
        animate={{ opacity: 1, scale: 1 }}
        transition={{ duration: 0.4 }}
      >
        <Logo size={52} withWordmark={false} />
      </m.div>
      <div className="shimmer h-1 w-32 rounded-full" aria-hidden />
      <p className="text-label text-muted">Chargement d'Ekuiseo…</p>
    </div>
  )
}
