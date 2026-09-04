import * as AccordionPrimitive from '@radix-ui/react-accordion'
import * as AvatarPrimitive from '@radix-ui/react-avatar'
import * as CheckboxPrimitive from '@radix-ui/react-checkbox'
import * as PopoverPrimitive from '@radix-ui/react-popover'
import * as ProgressPrimitive from '@radix-ui/react-progress'
import * as RadioGroupPrimitive from '@radix-ui/react-radio-group'
import * as SeparatorPrimitive from '@radix-ui/react-separator'
import * as SliderPrimitive from '@radix-ui/react-slider'
import * as SwitchPrimitive from '@radix-ui/react-switch'
import * as ToggleGroupPrimitive from '@radix-ui/react-toggle-group'
import * as TooltipPrimitive from '@radix-ui/react-tooltip'
import { Check, ChevronDown, Minus, Plus, Star } from 'lucide-react'
import { forwardRef, type ComponentPropsWithoutRef, type ElementRef, type HTMLAttributes } from 'react'
import { cn } from '@/lib/cn'
import { initials as buildInitials } from '@/lib/format'

/* ------------------------------------------------------------------ Avatar */

export function Avatar({
  firstName,
  lastName,
  photoUrl,
  size = 40,
  className,
}: {
  firstName: string
  lastName: string
  photoUrl?: string | null
  size?: number
  className?: string
}) {
  const label = `${firstName} ${lastName}`.trim()
  return (
    <AvatarPrimitive.Root
      className={cn(
        'relative inline-flex shrink-0 select-none items-center justify-center overflow-hidden rounded-full bg-primary-soft-2 ring-1 ring-inset ring-[color-mix(in_srgb,var(--primary)_18%,transparent)]',
        className,
      )}
      style={{ width: size, height: size }}
    >
      {photoUrl ? <AvatarPrimitive.Image src={photoUrl} alt={label} className="size-full object-cover" /> : null}
      <AvatarPrimitive.Fallback
        delayMs={photoUrl ? 300 : 0}
        className="font-display font-bold uppercase text-primary-ink"
        style={{ fontSize: Math.max(11, Math.round(size * 0.38)) }}
      >
        {buildInitials(firstName || '?', lastName || '?')}
      </AvatarPrimitive.Fallback>
    </AvatarPrimitive.Root>
  )
}

/* --------------------------------------------------------------- Separator */

export const Separator = forwardRef<
  ElementRef<typeof SeparatorPrimitive.Root>,
  ComponentPropsWithoutRef<typeof SeparatorPrimitive.Root>
>(function Separator({ className, orientation = 'horizontal', decorative = true, ...props }, ref) {
  return (
    <SeparatorPrimitive.Root
      ref={ref}
      orientation={orientation}
      decorative={decorative}
      className={cn('shrink-0 bg-rule', orientation === 'horizontal' ? 'h-px w-full' : 'h-full w-px', className)}
      {...props}
    />
  )
})

/* ---------------------------------------------------------------- Switch */

export const Switch = forwardRef<
  ElementRef<typeof SwitchPrimitive.Root>,
  ComponentPropsWithoutRef<typeof SwitchPrimitive.Root>
>(function Switch({ className, ...props }, ref) {
  return (
    <SwitchPrimitive.Root
      ref={ref}
      className={cn(
        'peer inline-flex h-6 w-11 shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent transition-colors',
        'data-[state=checked]:bg-[var(--indigo)] data-[state=unchecked]:bg-rule-strong disabled:opacity-50',
        className,
      )}
      {...props}
    >
      <SwitchPrimitive.Thumb className="pointer-events-none block size-5 rounded-full bg-white shadow-e1 transition-transform data-[state=checked]:translate-x-5 data-[state=unchecked]:translate-x-0" />
    </SwitchPrimitive.Root>
  )
})

/** Ligne de reglage : libelle + description + interrupteur, cible 44 px. */
export function SettingRow({
  title,
  description,
  children,
  className,
}: {
  title: string
  description?: string
  children: React.ReactNode
  className?: string
}) {
  return (
    <div className={cn('flex min-h-[56px] items-center justify-between gap-4 px-4 py-3', className)}>
      <div className="min-w-0">
        <p className="text-[14px] font-medium text-ink">{title}</p>
        {description ? <p className="text-[12px] text-muted">{description}</p> : null}
      </div>
      {children}
    </div>
  )
}

/* -------------------------------------------------------------- Checkbox */

export const Checkbox = forwardRef<
  ElementRef<typeof CheckboxPrimitive.Root>,
  ComponentPropsWithoutRef<typeof CheckboxPrimitive.Root>
>(function Checkbox({ className, ...props }, ref) {
  return (
    <CheckboxPrimitive.Root
      ref={ref}
      className={cn(
        'flex size-5 shrink-0 items-center justify-center rounded-[var(--radius-chip)] border-2 border-rule-strong bg-surface transition-colors',
        'data-[state=checked]:border-[var(--indigo)] data-[state=checked]:bg-[var(--indigo)] disabled:opacity-50',
        className,
      )}
      {...props}
    >
      <CheckboxPrimitive.Indicator>
        <Check className="size-3.5 text-[var(--indigo-contrast)]" strokeWidth={3} aria-hidden />
      </CheckboxPrimitive.Indicator>
    </CheckboxPrimitive.Root>
  )
})

/* ------------------------------------------------------------ RadioGroup */

export const RadioGroup = forwardRef<
  ElementRef<typeof RadioGroupPrimitive.Root>,
  ComponentPropsWithoutRef<typeof RadioGroupPrimitive.Root>
>(function RadioGroup({ className, ...props }, ref) {
  return <RadioGroupPrimitive.Root ref={ref} className={cn('grid gap-2', className)} {...props} />
})

export const RadioGroupItem = forwardRef<
  ElementRef<typeof RadioGroupPrimitive.Item>,
  ComponentPropsWithoutRef<typeof RadioGroupPrimitive.Item>
>(function RadioGroupItem({ className, ...props }, ref) {
  return (
    <RadioGroupPrimitive.Item
      ref={ref}
      className={cn(
        'flex size-5 shrink-0 items-center justify-center rounded-full border-2 border-rule-strong bg-surface transition-colors data-[state=checked]:border-[var(--indigo)]',
        className,
      )}
      {...props}
    >
      <RadioGroupPrimitive.Indicator className="size-2.5 rounded-full bg-[var(--indigo)]" />
    </RadioGroupPrimitive.Item>
  )
})

/* --------------------------------------------------------------- Slider */

export const Slider = forwardRef<
  ElementRef<typeof SliderPrimitive.Root>,
  ComponentPropsWithoutRef<typeof SliderPrimitive.Root>
>(function Slider({ className, ...props }, ref) {
  const thumbs = Array.isArray(props.value ?? props.defaultValue) ? (props.value ?? props.defaultValue)!.length : 1
  return (
    <SliderPrimitive.Root
      ref={ref}
      className={cn('relative flex h-11 w-full touch-none select-none items-center', className)}
      {...props}
    >
      <SliderPrimitive.Track className="relative h-1.5 w-full grow overflow-hidden rounded-full bg-rule-strong">
        <SliderPrimitive.Range className="absolute h-full bg-[var(--indigo)]" />
      </SliderPrimitive.Track>
      {Array.from({ length: thumbs }).map((_, index) => (
        <SliderPrimitive.Thumb
          key={index}
          className="block size-5 rounded-full border-2 border-[var(--indigo)] bg-surface shadow-e1 transition-transform active:scale-110"
        />
      ))}
    </SliderPrimitive.Root>
  )
})

/* ---------------------------------------------------------- ToggleGroup */

export const ToggleGroup = ToggleGroupPrimitive.Root

export const ToggleGroupItem = forwardRef<
  ElementRef<typeof ToggleGroupPrimitive.Item>,
  ComponentPropsWithoutRef<typeof ToggleGroupPrimitive.Item>
>(function ToggleGroupItem({ className, ...props }, ref) {
  return (
    <ToggleGroupPrimitive.Item
      ref={ref}
      className={cn(
        'inline-flex min-h-11 min-w-11 items-center justify-center rounded-[var(--radius-control)] border border-rule-strong bg-surface px-3 text-[14px] font-semibold text-ink-2 transition-colors active:scale-[0.97]',
        'data-[state=on]:border-[var(--indigo)] data-[state=on]:bg-[var(--indigo)] data-[state=on]:text-[var(--indigo-contrast)]',
        className,
      )}
      {...props}
    />
  )
})

/* -------------------------------------------------------------- Progress */

export const Progress = forwardRef<
  ElementRef<typeof ProgressPrimitive.Root>,
  ComponentPropsWithoutRef<typeof ProgressPrimitive.Root> & { tone?: 'indigo' | 'ocre' | 'vert' | 'vermillon' }
>(function Progress({ className, value, tone = 'indigo', ...props }, ref) {
  const bar = {
    indigo: 'bg-[var(--indigo)]',
    ocre: 'bg-[var(--ocre)]',
    vert: 'bg-[var(--vert)]',
    vermillon: 'bg-[var(--vermillon)]',
  }[tone]
  return (
    <ProgressPrimitive.Root
      ref={ref}
      value={value}
      className={cn('relative h-1.5 w-full overflow-hidden rounded-full bg-rule-strong', className)}
      {...props}
    >
      <ProgressPrimitive.Indicator
        className={cn('h-full w-full transition-transform duration-500 ease-out', bar)}
        style={{ transform: `translateX(-${100 - (value ?? 0)}%)` }}
      />
    </ProgressPrimitive.Root>
  )
})

/* -------------------------------------------------------------- Tooltip */

export const TooltipProvider = TooltipPrimitive.Provider

export function Tooltip({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <TooltipPrimitive.Root>
      <TooltipPrimitive.Trigger asChild>{children}</TooltipPrimitive.Trigger>
      <TooltipPrimitive.Portal>
        <TooltipPrimitive.Content
          sideOffset={6}
          className="ek-anim-pop z-50 max-w-56 rounded-[var(--radius-control)] border border-rule bg-surface px-2.5 py-1.5 text-[12px] text-ink shadow-e3"
        >
          {label}
          <TooltipPrimitive.Arrow className="fill-[var(--surface)]" />
        </TooltipPrimitive.Content>
      </TooltipPrimitive.Portal>
    </TooltipPrimitive.Root>
  )
}

/* -------------------------------------------------------------- Popover */

export const Popover = PopoverPrimitive.Root
export const PopoverTrigger = PopoverPrimitive.Trigger

export const PopoverContent = forwardRef<
  ElementRef<typeof PopoverPrimitive.Content>,
  ComponentPropsWithoutRef<typeof PopoverPrimitive.Content>
>(function PopoverContent({ className, align = 'start', sideOffset = 6, ...props }, ref) {
  return (
    <PopoverPrimitive.Portal>
      <PopoverPrimitive.Content
        ref={ref}
        align={align}
        sideOffset={sideOffset}
        className={cn(
          'ek-anim-pop z-50 w-72 rounded-[var(--radius-card)] border border-rule bg-surface p-3 shadow-e3 focus:outline-none',
          className,
        )}
        {...props}
      />
    </PopoverPrimitive.Portal>
  )
})

/* ------------------------------------------------------------ Accordion */

export const Accordion = AccordionPrimitive.Root

export const AccordionItem = forwardRef<
  ElementRef<typeof AccordionPrimitive.Item>,
  ComponentPropsWithoutRef<typeof AccordionPrimitive.Item>
>(function AccordionItem({ className, ...props }, ref) {
  return <AccordionPrimitive.Item ref={ref} className={cn('border-b border-rule last:border-0', className)} {...props} />
})

export const AccordionTrigger = forwardRef<
  ElementRef<typeof AccordionPrimitive.Trigger>,
  ComponentPropsWithoutRef<typeof AccordionPrimitive.Trigger>
>(function AccordionTrigger({ className, children, ...props }, ref) {
  return (
    <AccordionPrimitive.Header className="flex">
      <AccordionPrimitive.Trigger
        ref={ref}
        className={cn(
          'group flex min-h-[52px] flex-1 items-center justify-between gap-3 py-3 text-left text-[14px] font-semibold text-ink transition-colors hover:text-[var(--indigo)]',
          className,
        )}
        {...props}
      >
        {children}
        <ChevronDown
          aria-hidden
          className="size-4 shrink-0 text-muted transition-transform duration-200 group-data-[state=open]:rotate-180"
        />
      </AccordionPrimitive.Trigger>
    </AccordionPrimitive.Header>
  )
})

export const AccordionContent = forwardRef<
  ElementRef<typeof AccordionPrimitive.Content>,
  ComponentPropsWithoutRef<typeof AccordionPrimitive.Content>
>(function AccordionContent({ className, children, ...props }, ref) {
  return (
    <AccordionPrimitive.Content ref={ref} className="ek-acc overflow-hidden" {...props}>
      <div className={cn('pb-4 text-[14px] leading-relaxed text-ink-2', className)}>{children}</div>
    </AccordionPrimitive.Content>
  )
})

/* -------------------------------------------------------------- Skeleton */

export function Skeleton({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div aria-hidden className={cn('shimmer rounded-[var(--radius-chip)]', className)} {...props} />
}

/* ------------------------------------------------------------ Notation */

export function RatingStars({
  value,
  count,
  size = 14,
  className,
}: {
  value: number
  count?: number
  size?: number
  className?: string
}) {
  const rounded = Math.round(value * 2) / 2
  return (
    <span
      className={cn('inline-flex items-center gap-1', className)}
      aria-label={`${value.toFixed(1).replace('.', ',')} sur 5${count !== undefined ? `, ${count} avis` : ''}`}
    >
      <span aria-hidden className="inline-flex">
        {[1, 2, 3, 4, 5].map((i) => (
          <Star
            key={i}
            width={size}
            height={size}
            className={cn(
              i <= rounded ? 'fill-[var(--ocre)] text-[var(--ocre)]' : 'text-rule-strong',
              i - 0.5 === rounded && 'fill-[var(--ocre)] opacity-60',
            )}
          />
        ))}
      </span>
      <span className="tnum text-[13px] font-semibold text-ink">{value.toFixed(1).replace('.', ',')}</span>
      {count !== undefined ? <span className="text-[12px] text-muted">({count})</span> : null}
    </span>
  )
}

/* ------------------------------------------------- Compteur (places, etc.) */

export function Stepper({
  value,
  onChange,
  min = 1,
  max = 8,
  label,
  suffix,
}: {
  value: number
  onChange: (value: number) => void
  min?: number
  max?: number
  label: string
  suffix?: string
}) {
  return (
    <div className="flex items-center gap-2" role="group" aria-label={label}>
      <button
        type="button"
        onClick={() => onChange(Math.max(min, value - 1))}
        disabled={value <= min}
        aria-label={`Retirer une unité de ${label}`}
        className="flex size-11 items-center justify-center rounded-[var(--radius-control)] border border-rule-strong bg-surface text-ink transition-colors active:scale-95 disabled:opacity-40"
      >
        <Minus className="size-4" aria-hidden />
      </button>
      <output className="tnum min-w-[3ch] text-center font-display text-lg font-bold" aria-live="polite">
        {value}
        {suffix ? <span className="ml-1 text-[13px] font-medium text-muted">{suffix}</span> : null}
      </output>
      <button
        type="button"
        onClick={() => onChange(Math.min(max, value + 1))}
        disabled={value >= max}
        aria-label={`Ajouter une unité de ${label}`}
        className="flex size-11 items-center justify-center rounded-[var(--radius-control)] border border-rule-strong bg-surface text-ink transition-colors active:scale-95 disabled:opacity-40"
      >
        <Plus className="size-4" aria-hidden />
      </button>
    </div>
  )
}
