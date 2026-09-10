import { LocateFixed, Navigation, Radio, RefreshCw } from 'lucide-react'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/misc'
import { SectionTitle } from '@/components/layout/PageContainer'
import { RouteMap, type RouteMapPoint, type RouteMapVehicle } from '@/components/trip/RouteMap'
import { ShareTripButton } from '@/components/trip/ShareTripButton'
import type { LiveView } from '@/hooks/useLiveStream'
import { useNow } from '@/hooks/useNow'
import { useOnlineStatus } from '@/hooks/useNetwork'
import { cn } from '@/lib/cn'
import { describeError } from '@/lib/errors'
import { formatTime } from '@/lib/format'
import {
  describeApproach,
  estimateArrivalIso,
  estimateRemainingMinutes,
  formatAge,
  isPositionStale,
  isSharingWindowOpen,
  remainingDistanceKm,
  STALE_AFTER_SECONDS,
  toVehicle,
  type LatLng,
} from '@/lib/liveTracking'
import type { BookingStatus, LivePosition, TripResponse } from '@/api/types'
import { LiveSharingControl } from './LiveSharingControl'
import { describeGeoStatus, usePositionSharing } from './usePositionSharing'

/** Qui regarde la fiche : le conducteur, ou un passager avec sa reservation et son point de prise en charge. */
export type LiveViewer =
  | { role: 'DRIVER' }
  | { role: 'PASSENGER'; bookingId: string; bookingStatus: BookingStatus; pickup: LatLng & { label: string } }

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
          Position du conducteur indisponible momentanément : aucune mise à jour depuis plus d’une minute et demie.
        </p>
      ) : null}
    </dl>
  )
}

/**
 * Suivi en direct sur la fiche du trajet (V23/V28), pour un passager confirme ou le
 * conducteur : carte avec le vehicule (et ma position), distance et arrivee estimees,
 * fraicheur, etat de la connexion, lien public a partager, et - pour un passager
 * confirme - partage de sa propre position avec le conducteur. Pendant un trajet en
 * cours (`prominent`), la carte prend toute la place.
 */
export function LiveTrackingCard({
  trip,
  live,
  points,
  showMap,
  viewer,
  prominent = false,
}: {
  trip: TripResponse
  live: LiveView
  points: RouteMapPoint[]
  /** Carte dans la carte (mobile) ; sur grand ecran, la page passe les positions a sa carte laterale. */
  showMap: boolean
  viewer: LiveViewer
  prominent?: boolean
}) {
  const now = useNow(true)
  const online = useOnlineStatus()
  const [wantShare, setWantShare] = useState(false)
  const windowOpen = isSharingWindowOpen(trip, now)
  const tripStatus = live.tripStatus ?? trip.status
  const finished = tripStatus === 'COMPLETED' || tripStatus === 'CANCELLED'
  const canShareMine = viewer.role === 'PASSENGER' && viewer.bookingStatus === 'CONFIRMED' && windowOpen && !finished
  const sharingMine = wantShare && canShareMine
  const mine = usePositionSharing({ tripId: trip.id, active: sharingMine, initialIntervalSeconds: live.intervalSeconds })

  const driverAge = live.driver ? live.ageOf(live.driver, now) : null
  const driverStale = isPositionStale(driverAge)
  const vehicle = toVehicle(live.driver, driverAge)
  const meKnown = viewer.role === 'PASSENGER' ? live.me : null
  const passengerMarker: RouteMapVehicle | null = mine.myPosition
    ? { lat: mine.myPosition.lat, lng: mine.myPosition.lng, heading: null, stale: false }
    : toVehicle(meKnown, meKnown ? live.ageOf(meKnown, now) : null)

  const snapshot = live.snapshot
  const nothingKnown = !live.driver && Object.keys(live.participants).length === 0

  if (snapshot.isPending && nothingKnown) {
    return (
      <Card className="space-y-3 p-4">
        <Skeleton className="h-4 w-40" />
        <Skeleton className="h-4 w-full" />
      </Card>
    )
  }
  if (snapshot.isError && nothingKnown && !live.streaming) {
    return (
      <Card className="flex flex-wrap items-center justify-between gap-2 p-4 text-body text-muted">
        Suivi en direct indisponible : {describeError(snapshot.error)}
        <button type="button" className="font-semibold text-primary-ink underline-offset-4 hover:underline" onClick={() => live.refetch()}>
          Réessayer
        </button>
      </Card>
    )
  }

  const shareToken = snapshot.data?.shareToken ?? null
  const shareText = `Suivez ${trip.driver.firstName} en direct sur Ekuiseo : ${trip.originLabel} → ${trip.destLabel}.`
  const mapHeight = prominent ? 'h-[52vh] min-h-[280px]' : 'h-[200px]'
  const reconnecting = live.connection === 'reconnecting' && live.attempt >= 1

  // Ligne d approche du passager : vers son point de prise en charge tant que le trajet n est
  // pas parti (ou que le conducteur en est tout proche), vers la destination ensuite.
  const driver = live.driver
  const approach =
    viewer.role === 'PASSENGER' && driver ? describeApproach(driver, viewer.pickup, trip.driver.firstName) : null
  const showApproach = approach && (tripStatus !== 'ONGOING' || approach.state !== 'far')
  // Bloc chiffre (distance restante, arrivee estimee, fraicheur) : pendant le trajet, ou toujours pour le conducteur.
  const showSummary = tripStatus === 'ONGOING' || viewer.role === 'DRIVER'

  const map = showMap ? (
    <RouteMap
      points={points}
      className={cn('mb-4', mapHeight)}
      activation={prominent ? 'always' : 'on-demand'}
      vehicle={vehicle}
      passenger={passengerMarker}
      intervalSeconds={live.intervalSeconds}
      defaultFollow={vehicle ? 'driver' : passengerMarker ? 'me' : 'free'}
      showFollowControls={!!vehicle || !!passengerMarker}
    />
  ) : null

  const mineStatus = sharingMine
    ? (describeGeoStatus(mine.geoStatus, online) ??
      (mine.sendFailed
        ? { text: describeError(mine.sendError), tone: 'danger' as const }
        : mine.lastSentAt
          ? { text: `Position partagée avec ${trip.driver.firstName} · ${formatAge(Math.max(0, Math.floor((now - new Date(mine.lastSentAt).getTime()) / 1000)))}`, tone: 'success' as const }
          : { text: 'Recherche de la position GPS…', tone: 'muted' as const }))
    : null

  return (
    <Card className="p-4 sm:p-5">
      <SectionTitle
        action={
          shareToken ? (
            <ShareTripButton
              title={`Suivi en direct · ${trip.originLabel} → ${trip.destLabel}`}
              text={shareText}
              path={`/live/${shareToken}`}
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

      {viewer.role === 'DRIVER' ? (
        <div className="mb-4">
          <LiveSharingControl trip={trip} live={live} compact showShare={false} />
        </div>
      ) : null}

      {reconnecting ? (
        <p className="mb-3 flex items-center gap-2 rounded-[var(--radius-control)] bg-accent-soft px-3 py-2 text-label text-accent-ink" role="status">
          <RefreshCw className="size-4 shrink-0 motion-safe:animate-spin" aria-hidden />
          Connexion au suivi perdue, nouvelle tentative en cours…
        </p>
      ) : null}

      {map}

      {finished ? (
        <p className="text-body text-muted" role="status">
          Trajet terminé.
        </p>
      ) : !live.sharingEnabled && viewer.role === 'PASSENGER' ? (
        <p className="flex items-center gap-3 text-body text-muted" role="status">
          <Radio className="size-4 shrink-0" aria-hidden />
          Le conducteur n’a pas activé le suivi en direct.
        </p>
      ) : !driver ? (
        <p className="text-body text-muted" role="status">
          {viewer.role === 'DRIVER' ? 'Votre position apparaîtra ici dès la première mesure GPS.' : 'En attente de la première position du conducteur…'}
        </p>
      ) : (
        <div className="space-y-2">
          {driverStale ? (
            <p className="rounded-[var(--radius-control)] bg-danger-soft px-3 py-2 text-label font-medium text-danger-ink" role="status">
              Position du conducteur indisponible momentanément · dernière position {formatAge(driverAge ?? 0)}
            </p>
          ) : !showSummary ? (
            <p className="text-caption text-muted" role="status">
              Dernière position {formatAge(driverAge ?? 0)}
            </p>
          ) : null}
          {showApproach && approach ? (
            <p className={cn('tnum text-body font-semibold', driverStale ? 'text-muted' : 'text-ink')} title="Estimation à vol d’oiseau">
              {approach.text}
              {viewer.role === 'PASSENGER' ? <span className="ml-1 text-caption font-normal text-muted">· {viewer.pickup.label}</span> : null}
            </p>
          ) : null}
          {showSummary ? (
            <LivePositionSummary
              position={{ lat: driver.lat, lng: driver.lng, heading: driver.heading, speedKmh: driver.speedKmh, accuracyM: driver.accuracyM, recordedAt: driver.recordedAt }}
              age={driverAge}
              destination={{ lat: trip.destLat, lng: trip.destLng, label: trip.destLabel }}
              tripStatus={tripStatus}
            />
          ) : null}
        </div>
      )}

      {canShareMine ? (
        <div className="mt-4 border-t border-rule pt-3">
          <Button
            variant={sharingMine ? 'secondary' : 'outlineBrand'}
            block
            aria-pressed={sharingMine}
            onClick={() => setWantShare((v) => !v)}
          >
            <LocateFixed aria-hidden />
            {sharingMine ? 'Arrêter de partager ma position' : `Partager ma position avec ${trip.driver.firstName}`}
          </Button>
          <p
            className={cn(
              'mt-2 text-caption',
              mineStatus?.tone === 'danger' ? 'text-danger-ink' : mineStatus?.tone === 'success' ? 'text-success-ink' : 'text-muted',
            )}
            role="status"
          >
            {mineStatus?.text ??
              'Le conducteur verra où vous l’attendez. La permission de localisation est demandée à ce moment-là ; le partage s’arrête seul à la fin du trajet.'}
          </p>
        </div>
      ) : null}
    </Card>
  )
}
