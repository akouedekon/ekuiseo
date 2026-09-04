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

export function CardHeader({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex flex-col gap-1 px-5 pt-5', className)} {...props} />
}

export function CardTitle({ className, ...props }: HTMLAttributes<HTMLHeadingElement>) {
  return (
    <h3 className={cn('font-display text-title font-bold leading-tight tracking-[-0.02em]', className)} {...props} />
  )
}

export function CardDescription({ className, ...props }: HTMLAttributes<HTMLParagraphElement>) {
  return <p className={cn('text-label text-muted', className)} {...props} />
}

export function CardContent({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('px-5 py-5', className)} {...props} />
}

export function CardFooter({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex items-center gap-2 border-t border-rule px-5 py-3.5', className)} {...props} />
}

/** Panneau de section, plus large, utilise dans les pages compte et admin. */
export function Panel({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <section
      className={cn('rounded-[var(--radius-panel)] border border-rule bg-surface shadow-e1', className)}
      {...props}
    />
  )
}
