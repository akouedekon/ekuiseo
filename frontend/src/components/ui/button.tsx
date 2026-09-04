import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'
import { Loader2 } from 'lucide-react'
import { forwardRef, type ButtonHTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

/*
 * Cibles tactiles : 44 px minimum (md), 54 px pour les actions principales (lg).
 * Le retour a l'appui est un enfoncement bref (active:scale), coupe par
 * prefers-reduced-motion via la neutralisation globale des transitions.
 */
const buttonVariants = cva(
  'relative inline-flex select-none items-center justify-center gap-2 whitespace-nowrap rounded-[var(--radius-control)] font-medium transition-[transform,background-color,color,box-shadow] duration-150 active:scale-[0.98] disabled:pointer-events-none disabled:opacity-50',
  {
    variants: {
      variant: {
        primary: 'bg-[var(--indigo)] text-[var(--indigo-contrast)] shadow-e1 hover:bg-[var(--indigo-deep)]',
        secondary: 'bg-[var(--surface)] text-ink border border-rule-strong hover:bg-[var(--surface-calm)]',
        subtle: 'bg-[var(--surface-calm)] text-ink hover:bg-[var(--rule)]',
        ghost: 'text-ink-2 hover:bg-[var(--surface-calm)] hover:text-ink',
        danger: 'bg-[var(--vermillon)] text-[var(--vermillon-contrast)] hover:brightness-95',
        success: 'bg-[var(--vert)] text-[var(--vert-contrast)] hover:brightness-95',
        outlineBrand:
          'border border-[var(--indigo)] text-[var(--indigo)] bg-transparent hover:bg-[var(--indigo-soft)]',
        link: 'text-[var(--indigo)] underline-offset-4 hover:underline px-0 h-auto',
      },
      size: {
        sm: 'h-9 px-3 text-[13px]',
        md: 'h-11 px-4 text-[15px]',
        lg: 'h-[54px] px-6 text-base font-semibold',
        icon: 'h-11 w-11 p-0',
        iconSm: 'h-9 w-9 p-0',
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
          <Loader2 aria-hidden className="size-4 animate-spin" />
          <span className="sr-only">Chargement en cours</span>
        </>
      ) : null}
      {children}
    </button>
  )
})
