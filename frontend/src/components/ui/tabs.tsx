import * as TabsPrimitive from '@radix-ui/react-tabs'
import { forwardRef, type ComponentPropsWithoutRef, type ElementRef } from 'react'
import { cn } from '@/lib/cn'

export const Tabs = TabsPrimitive.Root

export const TabsList = forwardRef<
  ElementRef<typeof TabsPrimitive.List>,
  ComponentPropsWithoutRef<typeof TabsPrimitive.List>
>(function TabsList({ className, ...props }, ref) {
  return (
    <TabsPrimitive.List
      ref={ref}
      className={cn(
        'scroll-thin flex items-center gap-1 overflow-x-auto rounded-[var(--radius-control)] bg-surface-2 p-1',
        className,
      )}
      {...props}
    />
  )
})

export const TabsTrigger = forwardRef<
  ElementRef<typeof TabsPrimitive.Trigger>,
  ComponentPropsWithoutRef<typeof TabsPrimitive.Trigger>
>(function TabsTrigger({ className, ...props }, ref) {
  return (
    <TabsPrimitive.Trigger
      ref={ref}
      className={cn(
        'inline-flex h-9 flex-1 shrink-0 items-center justify-center gap-1.5 whitespace-nowrap rounded-[7px] px-3 text-label font-semibold text-ink-2 transition-[background-color,color,box-shadow] duration-150 hover:text-ink',
        'data-[state=active]:bg-surface data-[state=active]:text-ink data-[state=active]:shadow-e1',
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
 * structurant de l'ecran d'accueil, il doit se voir.
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
    <div
      role="radiogroup"
      aria-label={label}
      className={cn('grid gap-1 rounded-[12px] bg-surface-2 p-1', className)}
      style={{ gridTemplateColumns: `repeat(${options.length}, minmax(0, 1fr))` }}
    >
      {options.map((option) => {
        const active = option.value === value
        return (
          <button
            key={option.value}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onValueChange(option.value)}
            className={cn(
              'flex min-h-12 flex-col items-center justify-center rounded-[9px] px-2 py-1.5 text-center transition-[background-color,color,box-shadow,transform] duration-200 active:scale-[0.985]',
              active ? 'bg-surface text-ink shadow-e2' : 'text-ink-2 hover:text-ink',
            )}
          >
            <span className="font-display text-body font-bold tracking-[-0.01em]">{option.label}</span>
            {option.hint ? <span className="text-caption leading-tight text-muted">{option.hint}</span> : null}
          </button>
        )
      })}
    </div>
  )
}
