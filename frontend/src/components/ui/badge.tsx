import { cva, type VariantProps } from 'class-variance-authority'
import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

/**
 * Puce d'etat : rayon 6 px, 12 px minimum, jamais en dessous.
 * Sur un fond pale (-soft), le texte prend la version -ink de la teinte :
 * c'est ce qui garantit 4,5:1 (la couleur pleine ne l'atteint pas).
 */
const badgeVariants = cva(
  'inline-flex items-center gap-1 rounded-[var(--radius-chip)] px-2 py-[3px] text-caption font-semibold leading-4 [&>svg]:size-3.5 [&>svg]:shrink-0',
  {
    variants: {
      tone: {
        neutral: 'bg-surface-2 text-ink-2',
        indigo: 'bg-primary-soft text-primary-ink',
        info: 'bg-primary-soft text-primary-ink',
        success: 'bg-success-soft text-success-ink',
        warning: 'bg-accent-soft text-accent-ink',
        danger: 'bg-danger-soft text-danger-ink',
        solid: 'bg-primary text-on-primary',
        outline: 'border border-rule-strong bg-surface text-ink-2',
      },
    },
    defaultVariants: { tone: 'neutral' },
  },
)

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement>, VariantProps<typeof badgeVariants> {}

export function Badge({ className, tone, ...props }: BadgeProps) {
  return <span className={cn(badgeVariants({ tone }), className)} {...props} />
}
