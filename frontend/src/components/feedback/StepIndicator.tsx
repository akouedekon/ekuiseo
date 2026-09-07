import { Check } from 'lucide-react'
import { cn } from '@/lib/cn'

/**
 * Indicateur d'etapes partage par la reservation et la publication (audit F331).
 * Sur mobile, le libelle de l'etape courante reste toujours lisible
 * (« Étape 2 sur 3 · Paiement ») ; les autres libelles restent accessibles aux
 * lecteurs d'ecran. La progression est un simple filet plein, sans animation
 * decorative.
 */
export function StepIndicator({
  steps,
  current,
  label,
  className,
}: {
  steps: readonly string[]
  /** Index (0..n-1) de l'etape courante. */
  current: number
  /** Nom de la sequence, pour la liste (« Étapes de la réservation »). */
  label: string
  className?: string
}) {
  const index = Math.min(Math.max(current, 0), steps.length - 1)
  return (
    <div className={cn('mb-5', className)}>
      <ol className="flex items-center gap-2" aria-label={label}>
        {steps.map((step, i) => {
          const state = i < index ? 'done' : i === index ? 'current' : 'todo'
          return (
            <li key={step} className="flex flex-1 items-center gap-2">
              <span
                aria-current={state === 'current' ? 'step' : undefined}
                className={cn(
                  'tnum flex size-6 shrink-0 items-center justify-center rounded-full text-caption font-bold',
                  state === 'todo' ? 'border border-field-border text-muted' : 'bg-primary text-on-primary',
                )}
              >
                {state === 'done' ? <Check className="size-3.5" strokeWidth={3} aria-hidden /> : i + 1}
              </span>
              <span className={cn('sr-only sm:not-sr-only text-label', state === 'current' ? 'font-semibold text-ink' : 'font-medium text-muted')}>
                {step}
                <span className="sr-only">{state === 'done' ? ' (terminée)' : state === 'current' ? ' (étape en cours)' : ''}</span>
              </span>
              {i < steps.length - 1 ? (
                <span aria-hidden className="ml-1 h-0.5 flex-1 overflow-hidden rounded-full bg-rule-strong">
                  <span
                    className="block h-full rounded-full bg-primary transition-transform duration-300"
                    style={{ transform: `scaleX(${i < index ? 1 : 0})`, transformOrigin: 'left' }}
                  />
                </span>
              ) : null}
            </li>
          )
        })}
      </ol>
      {/* Toujours visible sur mobile : la ligne des libelles y est masquee. */}
      <p className="tnum mt-2 text-label font-medium text-ink-2 sm:hidden" aria-hidden>
        Étape {index + 1} sur {steps.length} · {steps[index]}
      </p>
    </div>
  )
}
