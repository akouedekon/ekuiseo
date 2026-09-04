import { cva, type VariantProps } from 'class-variance-authority'
import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

/** Puce d'etat : rayon 4 px, 12 px minimum, jamais en dessous. */
const badgeVariants = cva(
  'inline-flex items-center gap-1 rounded-[var(--radius-chip)] px-2 py-0.5 text-[12px] font-semibold leading-5 [&>svg]:size-3.5',
  {
    variants: {
      tone: {
        neutral: 'bg-[var(--surface-calm)] text-ink-2',
        indigo: 'bg-[var(--indigo-soft)] text-[var(--indigo-deep)]',
        success: 'bg-[var(--vert-soft)] text-[var(--vert)]',
        warning: 'bg-[var(--ocre-soft)] text-[var(--ocre-ink)]',
        danger: 'bg-[var(--vermillon-soft)] text-[var(--vermillon)]',
        solid: 'bg-[var(--indigo)] text-[var(--indigo-contrast)]',
        outline: 'border border-rule-strong text-ink-2',
      },
    },
    defaultVariants: { tone: 'neutral' },
  },
)

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement>, VariantProps<typeof badgeVariants> {}

export function Badge({ className, tone, ...props }: BadgeProps) {
  return <span className={cn(badgeVariants({ tone }), className)} {...props} />
}
