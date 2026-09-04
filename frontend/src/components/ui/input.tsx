import * as LabelPrimitive from '@radix-ui/react-label'
import { forwardRef, useId, type InputHTMLAttributes, type ReactNode, type TextareaHTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

export const Label = forwardRef<
  HTMLLabelElement,
  React.ComponentPropsWithoutRef<typeof LabelPrimitive.Root>
>(function Label({ className, ...props }, ref) {
  return (
    <LabelPrimitive.Root
      ref={ref}
      className={cn('text-[13px] font-medium text-ink-2', className)}
      {...props}
    />
  )
})

/* Anatomie commune des champs : voir .ek-field dans index.css (repos, survol, focus, erreur, desactive). */
const controlBase = 'ek-field w-full rounded-[var(--radius-control)] px-3 text-base placeholder:text-muted'

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string
  hint?: string
  error?: string
  /** Icone decorative a gauche (lucide-react). */
  leading?: ReactNode
  trailing?: ReactNode
}

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { className, label, hint, error, leading, trailing, id, ...props },
  ref,
) {
  const autoId = useId()
  const inputId = id ?? autoId
  const describedBy = error ? `${inputId}-error` : hint ? `${inputId}-hint` : undefined

  return (
    <div className="flex flex-col gap-1.5">
      {label ? <Label htmlFor={inputId}>{label}</Label> : null}
      <div className="relative flex items-center">
        {leading ? (
          <span aria-hidden className="pointer-events-none absolute left-3 text-muted [&>svg]:size-[18px]">
            {leading}
          </span>
        ) : null}
        <input
          ref={ref}
          id={inputId}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={cn(controlBase, 'h-11', leading && 'pl-10', trailing && 'pr-10', className)}
          {...props}
        />
        {trailing ? <span className="absolute right-3 text-muted [&>svg]:size-[18px]">{trailing}</span> : null}
      </div>
      {error ? (
        <p id={`${inputId}-error`} role="alert" className="text-[12px] font-medium text-[var(--vermillon)]">
          {error}
        </p>
      ) : hint ? (
        <p id={`${inputId}-hint`} className="text-[12px] text-muted">
          {hint}
        </p>
      ) : null}
    </div>
  )
})

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  label?: string
  hint?: string
  error?: string
}

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { className, label, hint, error, id, ...props },
  ref,
) {
  const autoId = useId()
  const areaId = id ?? autoId
  return (
    <div className="flex flex-col gap-1.5">
      {label ? <Label htmlFor={areaId}>{label}</Label> : null}
      <textarea
        ref={ref}
        id={areaId}
        aria-invalid={error ? true : undefined}
        className={cn(controlBase, 'min-h-24 resize-y py-2.5 leading-relaxed', className)}
        {...props}
      />
      {error ? (
        <p role="alert" className="text-[12px] font-medium text-[var(--vermillon)]">
          {error}
        </p>
      ) : hint ? (
        <p className="text-[12px] text-muted">{hint}</p>
      ) : null}
    </div>
  )
})
