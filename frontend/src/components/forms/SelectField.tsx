import { useId } from 'react'
import { Label } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'

export interface SelectOption<T extends string> {
  value: T
  label: string
}

interface SelectFieldProps<T extends string> {
  label: string
  value: T | ''
  onValueChange: (value: T) => void
  options: readonly SelectOption<T>[]
  placeholder?: string
  hint?: string
  error?: string
  disabled?: boolean
  id?: string
  className?: string
}

/**
 * Liste deroulante avec libelle, aide et erreur au niveau du champ : meme
 * anatomie que `Input`, pour que les formulaires restent homogenes.
 */
export function SelectField<T extends string>({
  label,
  value,
  onValueChange,
  options,
  placeholder = 'Choisir',
  hint,
  error,
  disabled,
  id,
  className,
}: SelectFieldProps<T>) {
  const autoId = useId()
  const fieldId = id ?? autoId
  const describedBy = error ? `${fieldId}-error` : hint ? `${fieldId}-hint` : undefined

  return (
    <div className={className ? `flex flex-col gap-1.5 ${className}` : 'flex flex-col gap-1.5'}>
      <Label htmlFor={fieldId}>{label}</Label>
      <Select value={value} onValueChange={(next) => onValueChange(next as T)} disabled={disabled}>
        <SelectTrigger
          id={fieldId}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={error ? 'border-[var(--vermillon)]' : undefined}
        >
          <SelectValue placeholder={placeholder} />
        </SelectTrigger>
        <SelectContent>
          {options.map((option) => (
            <SelectItem key={option.value} value={option.value}>
              {option.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {error ? (
        <p id={`${fieldId}-error`} role="alert" className="text-caption font-medium text-[var(--vermillon)]">
          {error}
        </p>
      ) : hint ? (
        <p id={`${fieldId}-hint`} className="text-caption text-muted">
          {hint}
        </p>
      ) : null}
    </div>
  )
}
