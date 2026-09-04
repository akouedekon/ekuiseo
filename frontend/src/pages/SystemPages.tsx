import { motion } from 'motion/react'
import { CloudOff, Compass, Home, RefreshCw, RotateCcw } from 'lucide-react'
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

/** Ecran hors ligne — affiche quand aucune donnee n'est disponible en cache. */
export function OfflinePage() {
  return (
    <SystemScreen
      title="Vous êtes hors ligne"
      description="Aucune donnée enregistrée pour cet écran. Reconnectez-vous au réseau pour continuer ; vos actions en attente partiront automatiquement."
    >
      <span className="mx-auto mb-2 flex size-14 items-center justify-center rounded-full bg-[var(--ocre-soft)] text-[var(--ocre-ink)]">
        <CloudOff className="size-6" aria-hidden />
      </span>
      <Button size="lg" block onClick={() => window.location.reload()}>
        <RefreshCw className="size-4" aria-hidden />
        Réessayer
      </Button>
    </SystemScreen>
  )
}

/** Ecran d'erreur applicative, rendu par la frontiere d'erreur du routeur. */
export function ErrorPage({ error, onReset }: { error?: unknown; onReset?: () => void }) {
  const detail =
    error instanceof Error ? error.message : typeof error === 'string' ? error : undefined

  return (
    <SystemScreen
      title="Un problème est survenu"
      description="L'application a rencontré une erreur inattendue. Rien n'a été perdu : vos données sont conservées."
    >
      {detail ? (
        <p className="mb-2 break-words rounded-[var(--radius-control)] bg-[var(--surface-calm)] px-3 py-2 text-left text-[12px] text-muted">
          {detail}
        </p>
      ) : null}
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
