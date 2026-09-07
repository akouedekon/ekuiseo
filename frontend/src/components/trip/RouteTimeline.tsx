import { m } from 'motion/react'
import { cn } from '@/lib/cn'
import { formatFcfa, formatTime } from '@/lib/format'
import type { RoutePoint } from '@/lib/route'

/**
 * Itineraire vertical avec arrets intermediaires et tarif par troncon.
 * Le prix affiche a chaque arret est celui du troncon origine -> arret,
 * pour qu'un passager descendant en route sache immediatement ce qu'il paie.
 */
export function RouteTimeline({ points, className }: { points: RoutePoint[]; className?: string }) {
  return (
    <ol className={cn('relative', className)}>
      {points.map((point, index) => {
        const last = index === points.length - 1
        return (
          <m.li
            key={`${point.label}-${index}`}
            initial={{ opacity: 0, x: -6 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ delay: index * 0.05, duration: 0.25 }}
            className="relative flex gap-3 pb-5 last:pb-0"
          >
            {/* Colonne heure */}
            <span
              className={cn(
                'tnum w-[42px] shrink-0 pt-px text-right font-display text-body font-bold leading-5',
                point.kind === 'stop' ? 'text-muted' : 'text-ink',
              )}
              title={point.estimated ? 'Heure d’arrivée estimée, non garantie par le conducteur' : undefined}
            >
              {point.time ? (
                <>
                  {point.estimated ? (
                    <>
                      <span className="font-sans font-normal text-muted" aria-hidden>
                        ≈{' '}
                      </span>
                      <span className="sr-only">estimée </span>
                    </>
                  ) : null}
                  {formatTime(point.time)}
                </>
              ) : (
                '—'
              )}
            </span>

            {/* Colonne graphique */}
            <span aria-hidden className="relative flex w-3 shrink-0 justify-center">
              {!last ? (
                <span
                  className={cn(
                    'absolute top-2.5 h-full w-0.5 rounded-full',
                    point.kind === 'origin' ? 'bg-primary' : 'bg-rule-strong',
                  )}
                />
              ) : null}
              <span
                className={cn(
                  'relative z-10 mt-1.5',
                  point.kind === 'origin' && 'size-3 rounded-full border-[3px] border-primary bg-surface',
                  point.kind === 'stop' && 'size-2 rounded-full bg-rule-strong ring-4 ring-surface',
                  point.kind === 'destination' && 'size-3 rounded-[3px] bg-danger',
                )}
              />
            </span>

            <span className="flex min-w-0 flex-1 items-baseline justify-between gap-3 pt-px">
              <span
                className={cn(
                  'min-w-0 truncate font-display leading-5',
                  point.kind === 'stop' ? 'text-body font-semibold text-ink-2' : 'text-base font-bold text-ink',
                )}
              >
                {point.label}
              </span>
              {point.priceFromOrigin !== null && point.priceFromOrigin > 0 ? (
                <span className="tnum shrink-0 text-label font-semibold text-ink-2">
                  {formatFcfa(point.priceFromOrigin)}
                </span>
              ) : point.kind === 'origin' ? (
                <span className="shrink-0 text-caption text-muted">départ</span>
              ) : null}
            </span>
          </m.li>
        )
      })}
    </ol>
  )
}
