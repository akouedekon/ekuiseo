import { Navigation, Radio } from 'lucide-react'
import type { UseQueryResult } from '@tanstack/react-query'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/misc'
import { SectionTitle } from '@/components/layout/PageContainer'
import { RouteMap, type RouteMapPoint } from '@/components/trip/RouteMap'
import { ShareTripButton } from '@/components/trip/ShareTripButton'
import { useNow } from '@/hooks/useNow'
import { cn } from '@/lib/cn'
import { describeError } from '@/lib/errors'
import { formatTime } from '@/lib/format'
import {
  estimateArrivalIso,
  estimateRemainingMinutes,
  formatAge,
  liveAgeSeconds,
  remainingDistanceKm,
  STALE_AFTER_SECONDS,
  toVehicle,
} from '@/lib/liveTracking'
import type { LivePosition, LivePositionResponse, TripResponse } from '@/api/types'

/**
 * Bloc chiffre du suivi : fraicheur, distance restante, arrivee estimee. Partage par la
 * fiche du trajet (passagers, conducteur) et le lien public. Distance et arrivee sont des
 * ESTIMATIONS a vol d oiseau, affichees avec « ≈ ».
 */
export function LivePositionSummary({
  position,
  age,
  destination,
  tripStatus,
  className,
}: {
  position: LivePosition | null
  age: number | null
  destination: { lat: number; lng: number; label: string }
  tripStatus: TripResponse['status']
  className?: string
}) {
  const finished = tripStatus === 'COMPLETED' || tripStatus === 'CANCELLED'
  if (!position) {
    return (
      <p className={cn('text-body text-muted', className)} role="status">
        {finished ? 'Trajet terminé.' : 'En attente de la première position du véhicule…'}
      </p>
    )
  }
  const stale = age !== null && age > STALE_AFTER_SECONDS
  const distanceKm = remainingDistanceKm(position, destination)
  const minutes = estimateRemainingMinutes(distanceKm, position.speedKmh)
  const arrival = estimateArrivalIso(position, destination)
  return (
    <dl className={cn('grid grid-cols-3 gap-3 text-body', className)}>
      <div>
        <dt className="text-caption text-muted">Dernière position</dt>
        <dd className={cn('tnum font-semibold', stale ? 'text-danger-ink' : 'text-ink')} role="status">
          {age === null ? '—' : formatAge(age)}
        </dd>
      </div>
      <div>
        <dt className="text-caption text-muted">Distance restante</dt>
        <dd className="tnum font-semibold" title="Estimation à vol d’oiseau">
          ≈ {distanceKm < 1 ? `${Math.round(distanceKm * 1000)} m` : `${Math.round(distanceKm)} km`}
        </dd>
      </div>
      <div>
        <dt className="text-caption text-muted">Arrivée estimée</dt>
        <dd className="tnum font-semibold" title="Estimation">
          {finished || distanceKm < 0.3 ? 'Arrivé' : `≈ ${formatTime(arrival)}${minutes < 60 ? ` (${minutes} min)` : ''}`}
        </dd>
      </div>
      {stale ? (
        <p className="col-span-3 text-caption text-danger-ink">
          Aucune position depuis plus d’une minute et demie : le véhicule a peut-être perdu le réseau.
        </p>
      ) : null}
    </dl>
  )
}

/**
 * Suivi en direct sur la fiche du trajet, pour un passager confirme ou le conducteur
 * (V23) : carte avec le vehicule, fraicheur, distance restante, arrivee estimee, et lien
 * public a partager avec un proche. La requete (rafraichie toutes les 10 s) vit dans la
 * page, qui peut aussi donner le vehicule a sa propre carte.
 */
export function LiveTrackingCard({
  trip,
  live,
  points,
  showMap,
}: {
  trip: TripResponse
  live: UseQueryResult<LivePositionResponse>
  points: RouteMapPoint[]
  /** Carte dans la carte (mobile) ; sur grand ecran, la page passe le vehicule a sa carte laterale. */
  showMap: boolean
}) {
  const now = useNow(live.data?.enabled ?? false)

  if (live.isPending) {
    return (
      <Card className="space-y-3 p-4">
        <Skeleton className="h-4 w-40" />
        <Skeleton className="h-4 w-full" />
      </Card>
    )
  }
  if (live.isError) {
    return (
      <Card className="flex flex-wrap items-center justify-between gap-2 p-4 text-body text-muted">
        Suivi en direct indisponible : {describeError(live.error)}
        <button type="button" className="font-semibold text-primary-ink underline-offset-4 hover:underline" onClick={() => live.refetch()}>
          Réessayer
        </button>
      </Card>
    )
  }
  const data = live.data
  if (!data.enabled) {
    return (
      <>
        <Card className="flex items-center gap-3 p-4 text-body text-muted" role="status">
          <Radio className="size-4 shrink-0" aria-hidden />
          Le conducteur n’a pas activé le suivi en direct.
        </Card>
        {showMap ? <RouteMap points={points} className="h-[180px]" activation="on-demand" /> : null}
      </>
    )
  }

  const age = liveAgeSeconds(data.staleSeconds, live.dataUpdatedAt, now)
  const vehicle = toVehicle(data.position, age)
  const shareText = `Suivez ${trip.driver.firstName} en direct sur Ekuiseo : ${trip.originLabel} → ${trip.destLabel}.`

  return (
    <Card className="p-4 sm:p-5">
      <SectionTitle
        action={
          data.shareToken ? (
            <ShareTripButton
              title={`Suivi en direct · ${trip.originLabel} → ${trip.destLabel}`}
              text={shareText}
              path={`/live/${data.shareToken}`}
              size="sm"
              variant="ghost"
            />
          ) : null
        }
      >
        <span className="inline-flex items-center gap-1.5">
          <Navigation className="size-3.5 text-primary-ink" aria-hidden />
          Suivi en direct
        </span>
      </SectionTitle>
      {showMap ? <RouteMap points={points} className="mb-4 h-[200px]" activation="on-demand" vehicle={vehicle} /> : null}
      <LivePositionSummary
        position={data.position}
        age={age}
        destination={{ lat: trip.destLat, lng: trip.destLng, label: trip.destLabel }}
        tripStatus={data.tripStatus}
      />
    </Card>
  )
}
