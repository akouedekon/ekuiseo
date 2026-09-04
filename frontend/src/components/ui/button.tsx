import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'
import { Loader2 } from 'lucide-react'
import { forwardRef, type ButtonHTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

/*
 * Cibles tactiles : 36 px (sm, actions secondaires de liste), 44 px (md),
 * 52 px (lg, action principale d'un ecran). Etats dans l'ordre ou l'oeil les
 * rencontre : repos, survol (un cran de teinte), appui (deux crans + leger
 * enfoncement), focus clavier (anneau global), desactive, chargement.
 */
const buttonVariants = cva(
  'relative inline-flex select-none items-center justify-center gap-2 whitespace-nowrap rounded-[var(--radius-control)] font-semibold tracking-[-0.005em] transition-[transform,background-color,color,box-shadow,border-color] duration-150 active:scale-[0.98] disabled:pointer-events-none disabled:opacity-50 [&>svg]:shrink-0',
  {
    variants: {
      variant: {
        primary:
          'bg-primary text-on-primary shadow-[0_1px_0_0_rgb(255_255_255/0.14)_inset,var(--shadow-1)] hover:bg-primary-hover active:bg-primary-active',
        secondary:
          'border border-rule-strong bg-surface text-ink shadow-e1 hover:border-[color-mix(in_srgb,var(--rule-strong)_55%,var(--ink-2))] hover:bg-surface-2 active:bg-rule',
        subtle: 'bg-surface-2 text-ink hover:bg-rule active:bg-rule-strong',
        ghost: 'text-ink-2 hover:bg-surface-2 hover:text-ink active:bg-rule',
        danger:
          'bg-danger text-on-danger shadow-[0_1px_0_0_rgb(255_255_255/0.14)_inset,var(--shadow-1)] hover:bg-[var(--danger-hover)] active:bg-[var(--danger-active)]',
        success:
          'bg-success text-on-success shadow-[0_1px_0_0_rgb(255_255_255/0.14)_inset,var(--shadow-1)] hover:bg-[var(--success-hover)] active:bg-[var(--success-active)]',
        outlineBrand:
          'border border-primary bg-transparent text-primary-ink hover:bg-primary-soft active:bg-primary-soft-2',
        link: 'h-auto px-0 text-primary-ink underline-offset-4 hover:underline active:text-primary-active',
      },
      size: {
        sm: 'h-9 px-3.5 text-label [&>svg]:size-4',
        md: 'h-11 px-4.5 text-body [&>svg]:size-[18px]',
        lg: 'h-13 px-6 text-base [&>svg]:size-5',
        icon: 'size-11 p-0 [&>svg]:size-[18px]',
        iconSm: 'size-9 p-0 [&>svg]:size-4',
      },
      block: { true: 'w-full', false: '' },
    },
    defaultVariants: { variant: 'primary', size: 'md', block: false },
  },
)

export interface ButtonProps
  extends ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean
  loading?: boolean
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { className, variant, size, block, asChild, loading, children, disabled, ...props },
  ref,
) {
  const classes = cn(buttonVariants({ variant, size, block }), className)

  /*
   * En mode asChild, Radix Slot exige UN SEUL enfant React : on transmet
   * `children` tel quel, sans indicateur de chargement ni fragment autour.
   */
  if (asChild) {
    return (
      <Slot ref={ref} className={classes} aria-busy={loading || undefined} {...props}>
        {children}
      </Slot>
    )
  }

  return (
    <button
      ref={ref}
      className={classes}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...props}
    >
      {loading ? (
        <>
          <Loader2 aria-hidden className="animate-spin" />
          <span className="sr-only">Chargement en cours</span>
        </>
      ) : null}
      {children}
    </button>
  )
})
