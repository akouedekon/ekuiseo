import { ChevronLeft } from 'lucide-react'
import type { ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { cn } from '@/lib/cn'

/** Colonne de contenu standard : mobile plein cadre, centree au-dela. */
export function PageContainer({
  children,
  className,
  width = 'md',
}: {
  children: ReactNode
  className?: string
  width?: 'sm' | 'md' | 'lg' | 'full'
}) {
  const max = { sm: 'max-w-lg', md: 'max-w-3xl', lg: 'max-w-[1200px]', full: 'max-w-none' }[width]
  return <div className={cn('mx-auto w-full px-4 py-6 sm:px-6 sm:py-8', max, className)}>{children}</div>
}

/**
 * En-tete d'ecran secondaire, avec retour explicite (cible 44 px).
 * Sur un lien direct (partage WhatsApp, favori), il n'y a pas d'ecran
 * precedent dans l'application : le retour mene alors a `backTo`.
 */
export function PageHeader({
  title,
  subtitle,
  back = true,
  backTo = '/',
  actions,
  className,
}: {
  title: ReactNode
  subtitle?: ReactNode
  back?: boolean
  /** Destination de repli quand l'historique de l'application est vide. */
  backTo?: string
  actions?: ReactNode
  className?: string
}) {
  const navigate = useNavigate()
  const location = useLocation()
  // react-router marque la toute premiere entree de session par la cle « default ».
  const canGoBack = location.key !== 'default'
  return (
    <div className={cn('mb-6 flex items-start gap-2', className)}>
      {back ? (
        <button
          type="button"
          onClick={() => (canGoBack ? navigate(-1) : navigate(backTo, { replace: true }))}
          aria-label="Revenir à l'écran précédent"
          className="-ml-2 mt-0.5 flex size-11 shrink-0 items-center justify-center rounded-[var(--radius-control)] text-ink-2 transition-colors hover:bg-surface-2 hover:text-ink"
        >
          <ChevronLeft className="size-5" aria-hidden />
        </button>
      ) : null}
      <div className="min-w-0 flex-1 pt-1">
        <h1 className="headline text-[28px] sm:text-display-lg">{title}</h1>
        {subtitle ? <p className="mt-1.5 text-body text-muted">{subtitle}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2 pt-1">{actions}</div> : null}
    </div>
  )
}

/** Titre de section a l'interieur d'une page : petit, en capitales, pour ne pas concurrencer le contenu. */
export function SectionTitle({
  children,
  action,
  className,
}: {
  children: ReactNode
  action?: ReactNode
  className?: string
}) {
  return (
    <div className={cn('mb-3 flex items-baseline justify-between gap-3', className)}>
      <h2 className="text-caption font-semibold uppercase tracking-[0.08em] text-muted">{children}</h2>
      {action}
    </div>
  )
}
