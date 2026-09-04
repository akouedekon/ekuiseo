import { motion } from 'motion/react'
import { Compass, Home, RotateCcw, ShieldOff } from 'lucide-react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { PageContainer } from '@/components/layout/PageContainer'
import { Logo } from '@/components/layout/Logo'

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
      {code ? (
        <motion.p
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          className="headline text-[72px] leading-none text-[var(--indigo)]"
        >
          {code}
        </motion.p>
      ) : null}
      <motion.h1
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.05 }}
        className="headline mt-3 text-[26px]"
      >
        {title}
      </motion.h1>
      <motion.p
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 0.1 }}
        className="mt-2 max-w-sm text-[15px] leading-relaxed text-muted"
      >
        {description}
      </motion.p>
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

/** Acces refuse (role insuffisant) ou profil impossible a charger avant la verification du role. */
export function AccessDeniedPage({ reason, onRetry }: { reason: 'forbidden' | 'error'; onRetry?: () => void }) {
  return (
    <PageContainer width="sm" className="flex min-h-[calc(100dvh-10rem)] flex-col items-center justify-center text-center">
      <span className="flex size-14 items-center justify-center rounded-[var(--radius-card)] bg-danger-soft text-danger-ink shadow-e1">
        <ShieldOff className="size-6" aria-hidden />
      </span>
      <h1 className="headline mt-4 text-[26px]">
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

/** Ecran de chargement initial (avant hydratation du cache et des polices). */
export function AppLoadingScreen() {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-4 bg-paper">
      <motion.div
        initial={{ opacity: 0, scale: 0.94 }}
        animate={{ opacity: 1, scale: 1 }}
        transition={{ duration: 0.4 }}
      >
        <Logo size={52} withWordmark={false} />
      </motion.div>
      <div className="h-1 w-32 overflow-hidden rounded-full bg-[var(--surface-calm)]">
        <motion.span
          className="block h-full w-1/3 rounded-full bg-[var(--indigo)]"
          animate={{ x: ['-100%', '300%'] }}
          transition={{ duration: 1.1, repeat: Infinity, ease: 'easeInOut' }}
        />
      </div>
      <p className="text-[13px] text-muted">Chargement d'Ekuiseo…</p>
    </div>
  )
}
