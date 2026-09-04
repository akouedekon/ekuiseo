import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

/**
 * En-tete d'un ecran du back-office : ou suis-je, combien d'elements, et
 * l'action principale a droite. Le titre de section « Back-office » vit dans
 * la coque (AdminLayout) ; ici c'est le nom de l'ecran.
 */
export function AdminPageHeader({
  title,
  description,
  count,
  actions,
  className,
}: {
  title: ReactNode
  description?: ReactNode
  /** Nombre d'elements de la liste courante, affiche en pastille apres le titre. */
  count?: number
  actions?: ReactNode
  className?: string
}) {
  return (
    <div className={cn('mb-4 flex flex-wrap items-start justify-between gap-x-4 gap-y-3', className)}>
      <div className="min-w-0">
        <h2 className="flex items-center gap-2 font-display text-heading font-extrabold tracking-[-0.03em]">
          {title}
          {count !== undefined ? (
            <span className="tnum rounded-full bg-[var(--surface-calm)] px-2 py-0.5 text-label font-semibold text-ink-2">
              {count.toLocaleString('fr-FR')}
            </span>
          ) : null}
        </h2>
        {description ? <p className="mt-0.5 text-label text-muted">{description}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div> : null}
    </div>
  )
}
