import { m } from 'motion/react'
import { BadgeCheck, UserCheck, Users } from 'lucide-react'
import { Link } from 'react-router'
import { Avatar, RatingStars } from '@/components/ui/misc'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/cn'
import { formatDuration, formatFcfa, formatTime } from '@/lib/format'
import { VehicleTypeBadge } from '@/components/trip/VehicleTypeIcon'
import { DRIVER_APPROVAL_BADGE } from '@/lib/labels'
import { listItem } from '@/lib/motion'
import { estimateArrival } from '@/lib/route'
import type { TripResponse } from '@/api/types'

/**
 * Carte de resultat.
 * Hierarchie voulue : heure de depart et prix d'abord (Archivo, chiffres
 * tabulaires), itineraire ensuite, conducteur en appui sous un filet.
 * Grille en trois lignes pour que l'axe horaire et l'axe geographique
 * restent alignes quelle que soit la longueur des libelles ; un lieu long
 * (« Bohicon, gare routière ») passe sur deux lignes plutot que d etre coupe,
 * et le prix, dans sa colonne, se lit en entier sur un ecran de 360 px.
 *
 * Un trajet complet garde son contraste plein (audit F334) : l'etat se lit au
 * badge danger et au filet lateral, pas a une opacite qui degrade tout le texte.
 */
export function TripCard({
  trip,
  animate = true,
  seats = 1,
}: {
  trip: TripResponse
  animate?: boolean
  /** Places demandees par la recherche, propagees a la fiche (audit F249). */
  seats?: number
}) {
  // Arrivee et duree sont des ESTIMATIONS du front : aucune heure d'arrivee n'est saisie par le conducteur.
  const { arrivalIso, durationMinutes } = estimateArrival(trip)
  const full = trip.seatsAvailable === 0
  // Resultat apparie sur un troncon (arret intermediaire) : le prix affiche est celui du troncon.
  const segment = trip.segmentPriceFcfa != null && trip.segmentPriceFcfa > 0 ? trip.segmentPriceFcfa : null
  const href = seats > 1 ? `/trips/${trip.id}?seats=${seats}` : `/trips/${trip.id}`

  const content = (
    <Link
      to={href}
      aria-label={`${trip.originLabel} vers ${trip.destLabel}, départ ${formatTime(trip.departureAt)}, ${formatFcfa(segment ?? trip.pricePerSeat)} par place${full ? ', complet' : ''}`}
      className={cn(
        'ek-lift ek-press group block rounded-[var(--radius-card)] border border-rule bg-surface shadow-e1',
        full && 'border-l-[3px] border-l-danger',
      )}
    >
      <div className="flex items-start gap-3 px-4 pb-3 pt-4 sm:px-5">
        <div className="grid min-w-0 flex-1 grid-cols-[auto_12px_minmax(0,1fr)] items-center gap-x-2.5 sm:gap-x-3">
          {/* Ligne 1 : depart */}
          <span className="tnum font-display text-title font-bold leading-none">{formatTime(trip.departureAt)}</span>
          <span aria-hidden className="mx-auto size-2.5 rounded-full border-2 border-primary bg-surface" />
          <span className="line-clamp-2 break-words font-display text-base font-bold leading-tight">{trip.originLabel}</span>

          {/* Ligne 2 : duree du trajet, le long du filet */}
          <span className="py-1 text-right text-micro leading-none text-muted" title="Durée estimée à vol d'oiseau">
            ≈ {formatDuration(durationMinutes)}
          </span>
          <span aria-hidden className="mx-auto h-5 w-0.5 rounded-full bg-rule-strong" />
          <span aria-hidden />

          {/* Ligne 3 : arrivee */}
          <span
            className="tnum font-display text-title font-bold leading-none text-muted"
            aria-label={`Arrivée estimée ${formatTime(arrivalIso)}`}
            title="Arrivée estimée"
          >
            <span className="font-sans text-label font-normal" aria-hidden>
              ≈{' '}
            </span>
            {formatTime(arrivalIso)}
          </span>
          <span aria-hidden className="mx-auto size-2.5 rounded-[3px] bg-danger" />
          <span className="line-clamp-2 break-words font-display text-base font-bold leading-tight text-ink-2">{trip.destLabel}</span>
        </div>

        <span className="shrink-0 text-right">
          <span className="tnum block whitespace-nowrap font-display text-heading font-extrabold leading-none tracking-[-0.03em] text-ink sm:text-display">
            {formatFcfa(segment ?? trip.pricePerSeat)}
          </span>
          <span className="mt-1 block text-caption text-muted">{segment ? 'par place, tronçon' : 'par place'}</span>
        </span>
      </div>

      {/* Le bandeau conducteur passe a la ligne plutot que de tronquer le nom. */}
      <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1.5 border-t border-rule px-4 py-3 sm:px-5">
        <Avatar
          firstName={trip.driver.firstName}
          lastName={trip.driver.lastName}
          photoUrl={trip.driver.photoUrl}
          size={28}
        />
        <span className="text-label font-semibold">
          {trip.driver.firstName} {trip.driver.lastName.charAt(0)}.
        </span>
        <RatingStars value={trip.driver.ratingAvg} size={12} />
        {trip.driver.identityVerified ? (
          <span className="inline-flex">
            <BadgeCheck className="size-4 text-success-ink" aria-hidden />
            <span className="sr-only">Identité vérifiée</span>
          </span>
        ) : null}

        <span className="ml-auto flex min-w-0 flex-wrap items-center justify-end gap-1.5">
          <VehicleTypeBadge type={trip.vehicle.vehicleType} />
          {!trip.instantBooking ? (
            <Badge tone="outline" title="Le conducteur accepte chaque passager avant confirmation">
              <UserCheck aria-hidden />
              {DRIVER_APPROVAL_BADGE}
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
  return <m.div variants={listItem}>{content}</m.div>
}
