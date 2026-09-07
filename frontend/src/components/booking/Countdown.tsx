import { useEffect, useState } from 'react'
import { Timer } from 'lucide-react'
import { Progress } from '@/components/ui/misc'
import { cn } from '@/lib/cn'
import { useCountdown } from '@/hooks/useNetwork'
import { formatCountdown } from '@/lib/format'

/** Paliers annonces aux lecteurs d'ecran (secondes restantes), du plus lointain au plus proche. */
const ANNOUNCE_AT: { seconds: number; message: string }[] = [
  { seconds: 10 * 60, message: "Il reste 10 minutes pour régler l'acompte." },
  { seconds: 5 * 60, message: "Il reste 5 minutes pour régler l'acompte." },
  { seconds: 60, message: "Il reste une minute pour régler l'acompte." },
]

/**
 * Compte a rebours d'expiration de l'acompte.
 * Le temps restant est ecrit en clair ET represente par une barre : sur un
 * ecran de paiement, l'information ne doit dependre d'aucune animation.
 *
 * Accessibilite (audit F320) : le chiffre qui change chaque seconde est
 * masque aux lecteurs d'ecran ; une zone `aria-live` distincte n'annonce que
 * les paliers (10 min, 5 min, 1 min, expire). Le libelle reste lisible a la
 * demande.
 */
export function DepositCountdown({
  deadline,
  totalSeconds = 20 * 60,
  onExpire,
  className,
  compact = false,
}: {
  deadline: number | null
  /** Duree totale de la fenetre (echeance - creation), pour la barre de progression. */
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

  /* Annonce vocale par paliers : chaque seuil franchi n'est annonce qu'une fois par echeance. */
  const [announcement, setAnnouncement] = useState('')
  const [announced, setAnnounced] = useState<{ deadline: number | null; seconds: number[] }>({ deadline, seconds: [] })
  useEffect(() => {
    if (deadline === null) return
    const state = announced.deadline === deadline ? announced : { deadline, seconds: [] }
    if (expired) {
      if (!state.seconds.includes(0)) {
        setAnnounced({ deadline, seconds: [...state.seconds, 0] })
        setAnnouncement('Délai dépassé : la place a été relibérée.')
      }
      return
    }
    const due = ANNOUNCE_AT.find((step) => remaining <= step.seconds && !state.seconds.includes(step.seconds))
    if (due) {
      // Un seuil deja depasse a l'arrivee (rechargement a 4 min) ne s'annonce pas retroactivement pour les paliers superieurs.
      const skipped = ANNOUNCE_AT.filter((step) => step.seconds >= due.seconds).map((step) => step.seconds)
      setAnnounced({ deadline, seconds: [...state.seconds, ...skipped] })
      setAnnouncement(due.message)
    }
  }, [remaining, expired, deadline, announced])

  const tone = expired ? 'danger' : urgent ? 'accent' : 'primary'
  const percent = deadline ? Math.min(100, (remaining / Math.max(1, totalSeconds)) * 100) : 0

  const liveRegion = (
    <span className="sr-only" role="status" aria-live="polite">
      {announcement}
    </span>
  )

  if (compact) {
    return (
      <span
        className={cn(
          'tnum inline-flex items-center gap-1 text-label font-semibold',
          expired ? 'text-danger-ink' : urgent ? 'text-accent-ink' : 'text-ink-2',
          className,
        )}
      >
        <Timer className="size-3.5" aria-hidden />
        <span className="sr-only">{expired ? 'Délai de paiement dépassé' : 'Temps restant pour régler l’acompte :'}</span>
        <span aria-hidden>{expired ? 'Expiré' : formatCountdown(remaining)}</span>
        {liveRegion}
      </span>
    )
  }

  return (
    <div
      className={cn(
        'rounded-[var(--radius-card)] border px-4 py-3',
        expired ? 'border-danger bg-danger-soft' : urgent ? 'border-accent bg-accent-soft' : 'border-rule bg-surface-2',
        className,
      )}
    >
      <div className="flex items-center justify-between gap-3">
        <span
          className={cn(
            'flex items-center gap-1.5 text-label font-medium',
            expired ? 'text-danger-ink' : urgent ? 'text-accent-ink' : 'text-ink-2',
          )}
        >
          <Timer className="size-4" aria-hidden />
          {expired ? 'Délai dépassé, la place a été relibérée' : "Temps restant pour régler l'acompte"}
        </span>
        <span
          aria-hidden
          className={cn(
            'tnum font-display text-heading font-extrabold leading-none tracking-[-0.02em]',
            expired ? 'text-danger-ink' : urgent ? 'text-accent-ink' : 'text-ink',
          )}
        >
          {formatCountdown(remaining)}
        </span>
      </div>
      <Progress value={percent} tone={tone} className="mt-2.5" aria-label="Temps restant" />
      {liveRegion}
    </div>
  )
}
