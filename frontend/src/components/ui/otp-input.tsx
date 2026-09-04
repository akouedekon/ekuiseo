import { useEffect, useRef, type ClipboardEvent, type KeyboardEvent } from 'react'
import { cn } from '@/lib/cn'

interface OtpInputProps {
  value: string
  onChange: (value: string) => void
  length?: number
  onComplete?: (value: string) => void
  disabled?: boolean
  error?: boolean
  label?: string
}

/**
 * Saisie d'un code a 6 cases : une seule valeur logique, six champs visuels.
 * Gere le collage du SMS entier, la touche Retour arriere entre cases et
 * l'autocompletion `one-time-code` du navigateur.
 */
export function OtpInput({
  value,
  onChange,
  length = 6,
  onComplete,
  disabled,
  error,
  label = 'Code de vérification',
}: OtpInputProps) {
  const refs = useRef<(HTMLInputElement | null)[]>([])
  const completedFor = useRef<string | null>(null)

  useEffect(() => {
    if (value.length === length && completedFor.current !== value) {
      completedFor.current = value
      onComplete?.(value)
    }
    if (value.length < length) completedFor.current = null
  }, [value, length, onComplete])

  const handleChange = (index: number, raw: string) => {
    const digits = raw.replace(/\D/g, '')
    if (!digits) {
      // Effacement de la case courante : on tronque, le code reste contigu.
      onChange(value.slice(0, index))
      return
    }
    // Saisie ou collage : on remplit a partir de la case courante.
    const chars = digits.split('')
    const next = value.split('')
    for (let i = 0; i < chars.length && index + i < length; i += 1) {
      next[index + i] = chars[i]
    }
    const merged = next.join('').slice(0, length)
    onChange(merged)
    const focusAt = Math.min(index + chars.length, length - 1)
    refs.current[focusAt]?.focus()
  }

  const handleKeyDown = (index: number, event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Backspace' && !value[index] && index > 0) {
      event.preventDefault()
      const next = value.split('')
      next[index - 1] = ''
      onChange(next.join('').slice(0, length))
      refs.current[index - 1]?.focus()
    }
    if (event.key === 'ArrowLeft' && index > 0) {
      event.preventDefault()
      refs.current[index - 1]?.focus()
    }
    if (event.key === 'ArrowRight' && index < length - 1) {
      event.preventDefault()
      refs.current[index + 1]?.focus()
    }
  }

  const handlePaste = (event: ClipboardEvent<HTMLInputElement>) => {
    event.preventDefault()
    const digits = event.clipboardData.getData('text').replace(/\D/g, '').slice(0, length)
    if (!digits) return
    onChange(digits)
    refs.current[Math.min(digits.length, length - 1)]?.focus()
  }

  return (
    <div role="group" aria-label={label} className="flex justify-between gap-2">
      {Array.from({ length }).map((_, index) => (
        <input
          key={index}
          ref={(el) => {
            refs.current[index] = el
          }}
          value={value[index] ?? ''}
          onChange={(e) => handleChange(index, e.target.value)}
          onKeyDown={(e) => handleKeyDown(index, e)}
          onPaste={handlePaste}
          onFocus={(e) => e.target.select()}
          disabled={disabled}
          inputMode="numeric"
          autoComplete={index === 0 ? 'one-time-code' : 'off'}
          maxLength={length}
          aria-label={`Chiffre ${index + 1} sur ${length}`}
          aria-invalid={error || undefined}
          className={cn(
            'tnum h-14 w-full min-w-0 rounded-[var(--radius-control)] border-2 bg-surface text-center font-display text-2xl font-bold text-ink transition-colors',
            'focus:border-[var(--indigo)] focus:outline-none',
            error ? 'border-[var(--vermillon)]' : value[index] ? 'border-[var(--indigo)]' : 'border-rule-strong',
            disabled && 'opacity-60',
          )}
        />
      ))}
    </div>
  )
}
