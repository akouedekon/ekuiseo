import { useEffect } from 'react'
import { Timer } from 'lucide-react'
import { Progress } from '@/components/ui/misc'
import { cn } from '@/lib/cn'
import { useCountdown } from '@/hooks/useNetwork'
import { formatCountdown } from '@/lib/format'

/**
 * Compte a rebours d'expiration de l'acompte.
 * Le temps restant est ecrit en clair ET represente par une barre : sur un
 * ecran de paiement, l'information ne doit dependre d'aucune animation.
 */
export function DepositCountdown({
  deadline,
  totalSeconds = 20 * 60,
  onExpire,
  className,
  compact = false,
}: {
  deadline: number | null
  totalSeconds?: number
  onExpire?: () => void
  className?: string
  compact?: boolean
}) {
  const remaining = useCountdown(deadline)
  const expired = deadline !== null && remaining === 0
  const urgent = remaining > 0 && remaining < 180

  // L'expiration est signalee au parent via un effet, jamais pendant le rendu.
  useEffect(() => {
    if (expired) onExpire?.()
  }, [expired, onExpire])

  const tone = expired ? 'vermillon' : urgent ? 'ocre' : 'indigo'
  const percent = deadline ? Math.min(100, (remaining / totalSeconds) * 100) : 0

  if (compact) {
    return (
      <span
        className={cn(
          'tnum inline-flex items-center gap-1 text-[13px] font-semibold',
          expired ? 'text-[var(--vermillon)]' : urgent ? 'text-[var(--ocre-ink)]' : 'text-ink-2',
          className,
        )}
        aria-label={expired ? 'Délai de paiement dépassé' : `Temps restant ${formatCountdown(remaining)}`}
      >
        <Timer className="size-3.5" aria-hidden />
        {expired ? 'Expiré' : formatCountdown(remaining)}
      </span>
    )
  }

  return (
    <div
      className={cn(
        'rounded-[var(--radius-card)] border px-4 py-3',
        expired
          ? 'border-[var(--vermillon)] bg-[var(--vermillon-soft)]'
          : urgent
            ? 'border-[var(--ocre)] bg-[var(--ocre-soft)]'
            : 'border-rule bg-[var(--surface-calm)]',
        className,
      )}
      role="status"
      aria-live={urgent ? 'assertive' : 'polite'}
    >
      <div className="flex items-center justify-between gap-3">
        <span
          className={cn(
            'flex items-center gap-1.5 text-[13px] font-medium',
            expired ? 'text-[var(--vermillon)]' : urgent ? 'text-[var(--ocre-ink)]' : 'text-ink-2',
          )}
        >
          <Timer className="size-4" aria-hidden />
          {expired ? 'Délai dépassé, la place a été relibérée' : "Temps restant pour régler l'acompte"}
        </span>
        <span
          className={cn(
            'tnum font-display text-[20px] font-extrabold leading-none tracking-[-0.02em]',
            expired ? 'text-[var(--vermillon)]' : urgent ? 'text-[var(--ocre-ink)]' : 'text-ink',
          )}
        >
          {formatCountdown(remaining)}
        </span>
      </div>
      <Progress value={percent} tone={tone} className="mt-2.5" aria-label="Temps restant" />
    </div>
  )
}
