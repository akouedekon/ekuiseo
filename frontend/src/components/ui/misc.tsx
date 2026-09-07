import * as AvatarPrimitive from '@radix-ui/react-avatar'
import * as CheckboxPrimitive from '@radix-ui/react-checkbox'
import * as ProgressPrimitive from '@radix-ui/react-progress'
import * as RadioGroupPrimitive from '@radix-ui/react-radio-group'
import * as SeparatorPrimitive from '@radix-ui/react-separator'
import * as SliderPrimitive from '@radix-ui/react-slider'
import * as SwitchPrimitive from '@radix-ui/react-switch'
import * as TooltipPrimitive from '@radix-ui/react-tooltip'
import { Check, Minus, Plus, Star } from 'lucide-react'
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

/**
 * Interrupteur : 24 px visibles, mais une zone tactile de 44 px par
 * pseudo-element (audit F324). Decoche, son fond prend `--field-border` pour
 * rester lisible au soleil (3:1, audit F315).
 */
export const Switch = forwardRef<
  ElementRef<typeof SwitchPrimitive.Root>,
  ComponentPropsWithoutRef<typeof SwitchPrimitive.Root>
>(function Switch({ className, ...props }, ref) {
  return (
    <SwitchPrimitive.Root
      ref={ref}
      className={cn(
        'peer relative inline-flex h-6 w-11 shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent transition-colors',
        'before:absolute before:-inset-x-2 before:-inset-y-2.5 before:content-[""]',
        'data-[state=checked]:bg-primary data-[state=unchecked]:bg-field-border disabled:opacity-50',
        className,
      )}
      {...props}
    >
      <SwitchPrimitive.Thumb className="pointer-events-none block size-5 rounded-full bg-white shadow-e1 transition-transform data-[state=checked]:translate-x-5 data-[state=unchecked]:translate-x-0" />
    </SwitchPrimitive.Root>
  )
})

/**
 * Ligne de reglage : libelle + description + controle. Toute la ligne est
 * cliquable (`<label>`, audit F324) : l'interrupteur a droite n'est plus la
 * seule cible de 24 px. `htmlFor` relie la ligne au controle quand il porte un id ;
 * sinon le `<label>` englobant suffit a transmettre le clic.
 */
export function SettingRow({
  title,
  description,
  children,
  className,
  htmlFor,
  interactive = true,
}: {
  title: string
  description?: string
  children: React.ReactNode
  className?: string
  htmlFor?: string
  /** `false` pour une ligne d'information sans controle (ex. reglage « bientôt »). */
  interactive?: boolean
}) {
  const Tag = interactive ? 'label' : 'div'
  return (
    <Tag
      htmlFor={interactive ? htmlFor : undefined}
      className={cn(
        'flex min-h-[56px] items-center justify-between gap-4 px-4 py-3',
        interactive && 'cursor-pointer transition-colors hover:bg-surface-2',
        className,
      )}
    >
      <span className="min-w-0">
        <span className="block text-body font-medium text-ink">{title}</span>
        {description ? <span className="block text-caption text-muted">{description}</span> : null}
      </span>
      {children}
    </Tag>
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
        'relative flex size-5 shrink-0 items-center justify-center rounded-[var(--radius-chip)] border-2 border-field-border bg-surface transition-colors',
        'before:absolute before:-inset-3 before:content-[""]',
        'data-[state=checked]:border-primary data-[state=checked]:bg-primary disabled:opacity-50',
        className,
      )}
      {...props}
    >
      <CheckboxPrimitive.Indicator>
        <Check className="size-3.5 text-on-primary" strokeWidth={3} aria-hidden />
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
        'relative flex size-5 shrink-0 items-center justify-center rounded-full border-2 border-field-border bg-surface transition-colors data-[state=checked]:border-primary',
        'before:absolute before:-inset-3 before:content-[""]',
        className,
      )}
      {...props}
    >
      <RadioGroupPrimitive.Indicator className="size-2.5 rounded-full bg-primary" />
    </RadioGroupPrimitive.Item>
  )
})

/*
 * Les groupes de choix dont chaque option est un bouton visuel (theme, etoiles,
 * bascule Interurbain / Quotidien) utilisent directement RadioGroupPrimitive :
 * Radix fournit le tabindex tournant et la navigation aux fleches (audit F321).
 */

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
        <SliderPrimitive.Range className="absolute h-full bg-primary" />
      </SliderPrimitive.Track>
      {Array.from({ length: thumbs }).map((_, index) => (
        <SliderPrimitive.Thumb
          key={index}
          className="block size-5 rounded-full border-2 border-primary bg-surface shadow-e1 transition-transform active:scale-110"
        />
      ))}
    </SliderPrimitive.Root>
  )
})

/* -------------------------------------------------------------- Progress */

export const Progress = forwardRef<
  ElementRef<typeof ProgressPrimitive.Root>,
  ComponentPropsWithoutRef<typeof ProgressPrimitive.Root> & { tone?: 'primary' | 'accent' | 'success' | 'danger' }
>(function Progress({ className, value, tone = 'primary', ...props }, ref) {
  const bar = {
    primary: 'bg-primary',
    accent: 'bg-accent',
    success: 'bg-success',
    danger: 'bg-danger',
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

export function Tooltip({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <TooltipPrimitive.Root>
      <TooltipPrimitive.Trigger asChild>{children}</TooltipPrimitive.Trigger>
      <TooltipPrimitive.Portal>
        <TooltipPrimitive.Content
          sideOffset={6}
          className="ek-anim-pop z-50 max-w-56 rounded-[var(--radius-control)] border border-rule bg-surface px-2.5 py-1.5 text-caption font-medium text-ink shadow-e3"
        >
          {label}
          <TooltipPrimitive.Arrow className="fill-surface" />
        </TooltipPrimitive.Content>
      </TooltipPrimitive.Portal>
    </TooltipPrimitive.Root>
  )
}

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
              i <= rounded ? 'fill-accent text-accent-ink' : 'text-rule-strong',
              i - 0.5 === rounded && 'fill-accent opacity-60',
            )}
          />
        ))}
      </span>
      <span className="tnum text-label font-semibold text-ink">{value.toFixed(1).replace('.', ',')}</span>
      {count !== undefined ? <span className="text-caption text-muted">({count})</span> : null}
    </span>
  )
}

/* ------------------------------------------------- Compteur (places, etc.) */

/**
 * Compteur a deux boutons. Les libelles des boutons sont explicites
 * (« Une place de moins », « Une semaine de plus », audit F328) : fournis par
 * l'appelant, ou derives de `label` par defaut.
 */
export function Stepper({
  value,
  onChange,
  min = 1,
  max = 8,
  label,
  suffix,
  decrementLabel,
  incrementLabel,
}: {
  value: number
  onChange: (value: number) => void
  min?: number
  max?: number
  label: string
  suffix?: string
  decrementLabel?: string
  incrementLabel?: string
}) {
  return (
    <div className="flex items-center gap-2" role="group" aria-label={label}>
      <button
        type="button"
        onClick={() => onChange(Math.max(min, value - 1))}
        disabled={value <= min}
        aria-label={decrementLabel ?? `Retirer une unité de ${label}`}
        className="flex size-11 items-center justify-center rounded-[var(--radius-control)] border border-field-border bg-surface text-ink transition-colors active:scale-95 disabled:opacity-40"
      >
        <Minus className="size-4" aria-hidden />
      </button>
      <output className="tnum min-w-[3ch] text-center font-display text-title font-bold" aria-live="polite">
        {value}
        {suffix ? <span className="ml-1 text-label font-medium text-muted">{suffix}</span> : null}
      </output>
      <button
        type="button"
        onClick={() => onChange(Math.min(max, value + 1))}
        disabled={value >= max}
        aria-label={incrementLabel ?? `Ajouter une unité de ${label}`}
        className="flex size-11 items-center justify-center rounded-[var(--radius-control)] border border-field-border bg-surface text-ink transition-colors active:scale-95 disabled:opacity-40"
      >
        <Plus className="size-4" aria-hidden />
      </button>
    </div>
  )
}
