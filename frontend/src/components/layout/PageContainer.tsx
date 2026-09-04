import { ChevronLeft } from 'lucide-react'
import type { ReactNode } from 'react'
import { useNavigate } from 'react-router'
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
  const max = { sm: 'max-w-lg', md: 'max-w-3xl', lg: 'max-w-6xl', full: 'max-w-none' }[width]
  return <div className={cn('mx-auto w-full px-4 py-5 sm:px-6', max, className)}>{children}</div>
}

/** En-tete d'ecran secondaire, avec retour explicite (cible 44 px). */
export function PageHeader({
  title,
  subtitle,
  back = true,
  actions,
  className,
}: {
  title: ReactNode
  subtitle?: ReactNode
  back?: boolean
  actions?: ReactNode
  className?: string
}) {
  const navigate = useNavigate()
  return (
    <div className={cn('mb-5 flex items-start gap-2', className)}>
      {back ? (
        <button
          type="button"
          onClick={() => navigate(-1)}
          aria-label="Revenir à l'écran précédent"
          className="-ml-2 flex size-11 shrink-0 items-center justify-center rounded-[var(--radius-control)] text-ink-2 transition-colors hover:bg-[var(--surface-calm)] hover:text-ink"
        >
          <ChevronLeft className="size-5" aria-hidden />
        </button>
      ) : null}
      <div className="min-w-0 flex-1 pt-1">
        <h1 className="headline text-[26px] sm:text-[30px]">{title}</h1>
        {subtitle ? <p className="mt-1 text-[14px] text-muted">{subtitle}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2 pt-0.5">{actions}</div> : null}
    </div>
  )
}

/** Titre de section a l'interieur d'une page. */
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
    <div className={cn('mb-2.5 flex items-baseline justify-between gap-3', className)}>
      <h2 className="font-display text-[13px] font-bold uppercase tracking-[0.06em] text-muted">{children}</h2>
      {action}
    </div>
  )
}
