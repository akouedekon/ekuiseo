import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

/** Surface elementaire : rayon 12 px, filet 1 px, ombre minimale. */
export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        'rounded-[var(--radius-card)] border border-rule bg-surface shadow-e1',
        className,
      )}
      {...props}
    />
  )
}

export function CardHeader({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex flex-col gap-1 px-4 pt-4', className)} {...props} />
}

export function CardTitle({ className, ...props }: HTMLAttributes<HTMLHeadingElement>) {
  return (
    <h3 className={cn('font-display text-[17px] font-bold leading-tight tracking-[-0.02em]', className)} {...props} />
  )
}

export function CardDescription({ className, ...props }: HTMLAttributes<HTMLParagraphElement>) {
  return <p className={cn('text-[13px] text-muted', className)} {...props} />
}

export function CardContent({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('px-4 py-4', className)} {...props} />
}

export function CardFooter({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex items-center gap-2 border-t border-rule px-4 py-3', className)} {...props} />
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
