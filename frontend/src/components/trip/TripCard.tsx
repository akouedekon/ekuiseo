import { motion } from 'motion/react'
import { BadgeCheck, Users, Zap } from 'lucide-react'
import { Link } from 'react-router'
import { Avatar, RatingStars } from '@/components/ui/misc'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/cn'
import { estimateDurationMinutes, haversineKm } from '@/lib/cities'
import { formatDuration, formatFcfa, formatTime } from '@/lib/format'
import { listItem } from '@/lib/motion'
import type { TripResponse } from '@/api/types'

/**
 * Carte de resultat.
 * Hierarchie voulue : heure de depart et prix d'abord (Archivo, chiffres
 * tabulaires), itineraire ensuite, conducteur en appui sous un filet.
 * Grille en trois lignes pour que l'axe horaire et l'axe geographique
 * restent alignes quelle que soit la longueur des libelles.
 */
export function TripCard({ trip, animate = true }: { trip: TripResponse; animate?: boolean }) {
  const km = haversineKm(trip.originLat, trip.originLng, trip.destLat, trip.destLng)
  const durationMin = estimateDurationMinutes(km)
  const arrival = new Date(new Date(trip.departureAt).getTime() + durationMin * 60_000)
  const full = trip.seatsAvailable === 0

  const content = (
    <Link
      to={`/trips/${trip.id}`}
      className={cn(
        'group block rounded-[var(--radius-card)] border border-rule bg-surface shadow-e1 transition-all duration-200',
        'hover:-translate-y-0.5 hover:border-rule-strong hover:shadow-e2 active:translate-y-0 active:scale-[0.995]',
        full && 'opacity-70',
      )}
    >
      <div className="grid grid-cols-[auto_10px_minmax(0,1fr)_auto] items-center gap-x-3 px-4 pb-3 pt-3.5">
        {/* Ligne 1 : depart */}
        <span className="tnum font-display text-[17px] font-bold leading-none">{formatTime(trip.departureAt)}</span>
        <span aria-hidden className="mx-auto size-2.5 rounded-full border-2 border-[var(--indigo)]" />
        <span className="truncate font-display text-[15px] font-bold leading-tight">{trip.originLabel}</span>
        <span className="tnum row-span-3 self-start text-right font-display text-[21px] font-extrabold leading-none tracking-[-0.03em]">
          {formatFcfa(trip.pricePerSeat)}
        </span>

        {/* Ligne 2 : duree du trajet, le long du filet */}
        <span className="py-1 text-right text-[11px] leading-none text-muted">{formatDuration(durationMin)}</span>
        <span aria-hidden className="mx-auto h-5 w-0.5 rounded-full bg-rule-strong" />
        <span aria-hidden />

        {/* Ligne 3 : arrivee */}
        <span className="tnum font-display text-[17px] font-bold leading-none text-muted">{formatTime(arrival)}</span>
        <span aria-hidden className="mx-auto size-2.5 rounded-[2px] bg-[var(--vermillon)]" />
        <span className="truncate font-display text-[15px] font-bold leading-tight text-ink-2">{trip.destLabel}</span>
      </div>

      {/* Le bandeau conducteur passe a la ligne plutot que de tronquer le nom. */}
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1.5 border-t border-rule px-4 py-2.5">
        <Avatar
          firstName={trip.driver.firstName}
          lastName={trip.driver.lastName}
          photoUrl={trip.driver.photoUrl}
          size={28}
        />
        <span className="text-[13px] font-medium">
          {trip.driver.firstName} {trip.driver.lastName.charAt(0)}.
        </span>
        <RatingStars value={trip.driver.ratingAvg} size={12} />

        <span className="ml-auto flex shrink-0 items-center gap-1">
          {trip.instantBooking ? (
            <Badge tone="indigo" title="Réservation instantanée">
              <Zap aria-hidden />
              Immédiat
            </Badge>
          ) : null}
          {trip.vehicle.comfortLevel === 'PREMIUM' ? (
            <Badge tone="neutral" className="hidden sm:inline-flex">
              <BadgeCheck aria-hidden />
              Premium
            </Badge>
          ) : null}
          <Badge tone={full ? 'danger' : trip.seatsAvailable <= 1 ? 'warning' : 'neutral'}>
            <Users aria-hidden />
            {full ? 'Complet' : `${trip.seatsAvailable} pl.`}
          </Badge>
        </span>
      </div>
    </Link>
  )

  if (!animate) return content
  return <motion.div variants={listItem}>{content}</motion.div>
}
