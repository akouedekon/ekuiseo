import { motion } from 'motion/react'
import { AlertTriangle, type LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'
import { Button } from './button'
import { Card } from './card'
import { Skeleton } from './misc'

/** Etat vide : jamais un simple texte gris, toujours une action a portee. */
export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  className,
}: {
  icon: LucideIcon
  title: string
  description?: string
  action?: ReactNode
  className?: string
}) {
  return (
    <div className={cn('flex flex-col items-center gap-3 px-6 py-12 text-center', className)}>
      <span className="flex size-14 items-center justify-center rounded-full bg-[var(--surface-calm)] text-ink-2">
        <Icon className="size-6" aria-hidden />
      </span>
      <div className="max-w-xs">
        <h3 className="font-display text-[17px] font-bold tracking-[-0.02em]">{title}</h3>
        {description ? <p className="mt-1 text-[14px] leading-relaxed text-muted">{description}</p> : null}
      </div>
      {action ? <div className="mt-1">{action}</div> : null}
    </div>
  )
}

/** Erreur de chargement, avec reessai explicite (reseau irregulier). */
export function ErrorState({
  title = 'Chargement impossible',
  description = "Vérifiez votre connexion, puis réessayez.",
  onRetry,
  className,
}: {
  title?: string
  description?: string
  onRetry?: () => void
  className?: string
}) {
  return (
    <div className={cn('flex flex-col items-center gap-3 px-6 py-10 text-center', className)} role="alert">
      <span className="flex size-14 items-center justify-center rounded-full bg-[var(--vermillon-soft)] text-[var(--vermillon)]">
        <AlertTriangle className="size-6" aria-hidden />
      </span>
      <div className="max-w-xs">
        <h3 className="font-display text-[17px] font-bold tracking-[-0.02em]">{title}</h3>
        <p className="mt-1 text-[14px] leading-relaxed text-muted">{description}</p>
      </div>
      {onRetry ? (
        <Button variant="secondary" onClick={onRetry}>
          Réessayer
        </Button>
      ) : null}
    </div>
  )
}

/** Squelette de carte trajet : reprend exactement la metrique de TripCard. */
export function TripCardSkeleton() {
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
        <motion.div
          key={index}
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: index * 0.05 }}
        >
          {children ?? <TripCardSkeleton />}
        </motion.div>
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
