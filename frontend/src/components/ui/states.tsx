import { m } from 'motion/react'
import { AlertTriangle, WifiOff, type LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'
import { Button } from './button'
import { Card } from './card'
import { Skeleton } from './misc'

/** Niveau de titre d'un etat (h2 par defaut : un etat vide est une section de la page, audit F330). */
export type HeadingLevel = 'h1' | 'h2' | 'h3' | 'h4'

/** Etat vide : jamais un simple texte gris, toujours une action a portee. */
export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  className,
  headingLevel: Heading = 'h2',
}: {
  icon: LucideIcon
  title: string
  description?: string
  action?: ReactNode
  className?: string
  headingLevel?: HeadingLevel
}) {
  return (
    <div className={cn('relative flex flex-col items-center gap-3 overflow-hidden px-6 py-12 text-center', className)}>
      <span aria-hidden className="ek-dots pointer-events-none absolute inset-x-0 top-0 h-32" />
      <span className="relative flex size-14 items-center justify-center rounded-[var(--radius-card)] bg-primary-soft text-primary-ink shadow-e1 ring-4 ring-surface">
        <Icon className="size-6" aria-hidden />
      </span>
      <div className="relative max-w-xs">
        <Heading className="font-display text-title font-bold tracking-[-0.02em]">{title}</Heading>
        {description ? <p className="mt-1 text-body leading-relaxed text-muted">{description}</p> : null}
      </div>
      {action ? <div className="relative mt-1">{action}</div> : null}
    </div>
  )
}

/**
 * Erreur de chargement. Le bouton « Reessayer » n'apparait que si l'appelant le
 * fournit : pour une erreur definitive (400, 403, 404), passer `onRetry`
 * `undefined` et une description issue de `describeError` (audit F246).
 */
export function ErrorState({
  title = 'Chargement impossible',
  description = "Vérifiez votre connexion, puis réessayez.",
  onRetry,
  className,
  headingLevel: Heading = 'h2',
}: {
  title?: string
  description?: string
  onRetry?: () => void
  className?: string
  headingLevel?: HeadingLevel
}) {
  return (
    <div className={cn('flex flex-col items-center gap-3 px-6 py-10 text-center', className)} role="alert">
      <span className="flex size-14 items-center justify-center rounded-[var(--radius-card)] bg-danger-soft text-danger-ink shadow-e1 ring-4 ring-surface">
        <AlertTriangle className="size-6" aria-hidden />
      </span>
      <div className="max-w-xs">
        <Heading className="font-display text-title font-bold tracking-[-0.02em]">{title}</Heading>
        <p className="mt-1 text-body leading-relaxed text-muted">{description}</p>
      </div>
      {onRetry ? (
        <Button variant="secondary" onClick={onRetry}>
          Réessayer
        </Button>
      ) : null}
    </div>
  )
}

/**
 * Premiere visite hors ligne : la requete est en pause (networkMode offlineFirst,
 * aucune donnee en cache) et resterait en squelette indefiniment. Detecte par
 * `isPending && fetchStatus === 'paused'` (audit F216).
 */
export function isOfflineWithoutData(query: { isPending: boolean; fetchStatus: 'fetching' | 'paused' | 'idle' }): boolean {
  return query.isPending && query.fetchStatus === 'paused'
}

/** Hors ligne, aucune donnee enregistree pour cet ecran : on le dit, avec un reessai. */
export function OfflineState({
  title = 'Vous êtes hors ligne',
  description = "Cet écran n'a pas encore été enregistré sur cet appareil. Il s'affichera dès que la connexion reviendra.",
  onRetry,
  className,
  headingLevel: Heading = 'h2',
}: {
  title?: string
  description?: string
  onRetry?: () => void
  className?: string
  headingLevel?: HeadingLevel
}) {
  return (
    <div className={cn('flex flex-col items-center gap-3 px-6 py-10 text-center', className)} role="status">
      <span className="flex size-14 items-center justify-center rounded-[var(--radius-card)] bg-accent-soft text-accent-ink shadow-e1 ring-4 ring-surface">
        <WifiOff className="size-6" aria-hidden />
      </span>
      <div className="max-w-xs">
        <Heading className="font-display text-title font-bold tracking-[-0.02em]">{title}</Heading>
        <p className="mt-1 text-body leading-relaxed text-muted">{description}</p>
      </div>
      {onRetry ? (
        <Button variant="secondary" onClick={onRetry}>
          Réessayer
        </Button>
      ) : null}
    </div>
  )
}

/**
 * Chargement qui s'eternise (audit F252) : apres 8 s, le squelette seul ressemble
 * a un ecran mort. Ce message, annonce par `role="status"`, dit que l'application
 * continue d'essayer - a afficher au-dessus du squelette, jamais a sa place.
 */
export function SlowNetworkNotice({ className }: { className?: string }) {
  return (
    <p
      role="status"
      className={cn(
        'flex items-center gap-2 rounded-[var(--radius-control)] bg-accent-soft px-3 py-2 text-label font-medium text-accent-ink',
        className,
      )}
    >
      <WifiOff className="size-4 shrink-0" aria-hidden />
      Le réseau est lent, nous continuons d'essayer.
    </p>
  )
}

/** Squelette de carte trajet : reprend exactement la metrique de TripCard. */
function TripCardSkeleton() {
  return (
    <Card className="p-4">
      <div className="flex items-start justify-between gap-4">
        <div className="flex-1 space-y-3">
          <div className="flex items-center gap-3">
            <Skeleton className="h-5 w-12" />
            <Skeleton className="h-3 w-16" />
            <Skeleton className="h-5 w-12" />
          </div>
          <Skeleton className="h-4 w-3/4" />
          <div className="flex items-center gap-2 pt-1">
            <Skeleton className="size-9 rounded-full" />
            <Skeleton className="h-4 w-24" />
          </div>
        </div>
        <Skeleton className="h-7 w-24" />
      </div>
    </Card>
  )
}

export function ListSkeleton({ count = 4, children }: { count?: number; children?: ReactNode }) {
  return (
    <div className="space-y-3" aria-busy="true" aria-live="polite">
      <span className="sr-only">Chargement des résultats</span>
      {Array.from({ length: count }).map((_, index) => (
        <m.div
          key={index}
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: index * 0.05 }}
        >
          {children ?? <TripCardSkeleton />}
        </m.div>
      ))}
    </div>
  )
}

/** Ligne de statistique squelette pour le back-office. */
export function StatSkeleton() {
  return (
    <Card className="p-4">
      <Skeleton className="h-3 w-20" />
      <Skeleton className="mt-3 h-7 w-28" />
      <Skeleton className="mt-2 h-3 w-16" />
    </Card>
  )
}
