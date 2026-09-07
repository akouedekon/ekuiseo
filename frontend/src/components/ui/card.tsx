import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

/**
 * Surface elementaire : rayon 14 px, hairline + ombre courte.
 * `interactive` ajoute l'elevation douce au survol (cartes cliquables).
 */
export function Card({
  className,
  interactive = false,
  ...props
}: HTMLAttributes<HTMLDivElement> & { interactive?: boolean }) {
  return (
    <div
      className={cn(
        'rounded-[var(--radius-card)] border border-rule bg-surface shadow-e1',
        interactive && 'ek-lift',
        className,
      )}
      {...props}
    />
  )
}
