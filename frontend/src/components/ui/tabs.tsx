import * as RadioGroupPrimitive from '@radix-ui/react-radio-group'
import * as TabsPrimitive from '@radix-ui/react-tabs'
import { forwardRef, type ComponentPropsWithoutRef, type ElementRef } from 'react'
import { cn } from '@/lib/cn'

export const Tabs = TabsPrimitive.Root

/**
 * Liste d onglets. Par defaut, un segment plein largeur (trois onglets au plus tiennent
 * sur un telephone). `scroll` : une rangee de puces qui defile horizontalement, sans
 * ascenseur, en debordant jusqu aux bords de l ecran sous 640 px pour que la coupe se
 * fasse au bord et non au milieu d un onglet (compte : six sections).
 */
export const TabsList = forwardRef<
  ElementRef<typeof TabsPrimitive.List>,
  ComponentPropsWithoutRef<typeof TabsPrimitive.List> & { scroll?: boolean }
>(function TabsList({ className, scroll = false, ...props }, ref) {
  return (
    <TabsPrimitive.List
      ref={ref}
      data-scroll={scroll || undefined}
      className={cn(
        scroll
          ? 'scroll-hide -mx-4 flex items-center gap-2 overflow-x-auto px-4 py-1 sm:mx-0 sm:px-0'
          : 'scroll-thin flex items-center gap-1 overflow-x-auto rounded-[var(--radius-control)] bg-surface-2 p-1',
        className,
      )}
      {...props}
    />
  )
})

/** Onglet : 44 px de haut, cible tactile de la charte (audit F324). Puce a contour dans une liste `scroll`. */
export const TabsTrigger = forwardRef<
  ElementRef<typeof TabsPrimitive.Trigger>,
  ComponentPropsWithoutRef<typeof TabsPrimitive.Trigger>
>(function TabsTrigger({ className, ...props }, ref) {
  return (
    <TabsPrimitive.Trigger
      ref={ref}
      className={cn(
        'inline-flex min-h-11 flex-1 shrink-0 items-center justify-center gap-1.5 whitespace-nowrap rounded-[7px] px-3 text-label font-semibold text-ink-2 transition-[background-color,color,box-shadow,border-color] duration-150 hover:text-ink active:scale-[0.98]',
        'data-[state=active]:bg-surface data-[state=active]:text-ink data-[state=active]:shadow-e1',
        '[[data-scroll]>&]:flex-none [[data-scroll]>&]:rounded-[var(--radius-pill)] [[data-scroll]>&]:border [[data-scroll]>&]:border-rule-strong [[data-scroll]>&]:bg-surface [[data-scroll]>&]:px-4',
        '[[data-scroll]>&]:data-[state=active]:border-primary [[data-scroll]>&]:data-[state=active]:bg-primary-soft [[data-scroll]>&]:data-[state=active]:text-primary-ink [[data-scroll]>&]:data-[state=active]:shadow-none',
        className,
      )}
      {...props}
    />
  )
})

export const TabsContent = forwardRef<
  ElementRef<typeof TabsPrimitive.Content>,
  ComponentPropsWithoutRef<typeof TabsPrimitive.Content>
>(function TabsContent({ className, ...props }, ref) {
  return <TabsPrimitive.Content ref={ref} className={cn('mt-4 focus:outline-none', className)} {...props} />
})

/**
 * Bascule majeure a deux etats (Interurbain / Quotidien) : plus grande que
 * l'onglet standard, avec un curseur plein qui glisse — c'est le choix
 * structurant de l'ecran d'accueil, il doit se voir. Construite sur le groupe
 * radio Radix : tabindex tournant et fleches du clavier fournis (audit F321).
 */
export function SegmentedToggle<T extends string>({
  value,
  onValueChange,
  options,
  label,
  className,
}: {
  value: T
  onValueChange: (value: T) => void
  options: { value: T; label: string; hint?: string }[]
  label: string
  className?: string
}) {
  return (
    <RadioGroupPrimitive.Root
      value={value}
      onValueChange={(next) => onValueChange(next as T)}
      aria-label={label}
      orientation="horizontal"
      className={cn('grid gap-1 rounded-[12px] bg-surface-2 p-1', className)}
      style={{ gridTemplateColumns: `repeat(${options.length}, minmax(0, 1fr))` }}
    >
      {options.map((option) => {
        const active = option.value === value
        return (
          <RadioGroupPrimitive.Item
            key={option.value}
            value={option.value}
            className={cn(
              'flex min-h-12 flex-col items-center justify-center rounded-[9px] px-2 py-1.5 text-center transition-[background-color,color,box-shadow,transform] duration-200 active:scale-[0.985]',
              active ? 'bg-surface text-ink shadow-e2' : 'text-ink-2 hover:text-ink',
            )}
          >
            <span className="font-display text-body font-bold tracking-[-0.01em]">{option.label}</span>
            {option.hint ? <span className="text-caption leading-tight text-muted">{option.hint}</span> : null}
          </RadioGroupPrimitive.Item>
        )
      })}
    </RadioGroupPrimitive.Root>
  )
}
