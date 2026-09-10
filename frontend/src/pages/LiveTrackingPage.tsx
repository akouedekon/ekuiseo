import { Car, Navigation } from 'lucide-react'
import { useMemo } from 'react'
import { Link, useParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/misc'
import { ErrorState, OfflineState, isOfflineWithoutData } from '@/components/ui/states'
import { PageContainer, PageHeader } from '@/components/layout/PageContainer'
import { PageMeta } from '@/components/layout/PageMeta'
import { RouteMap, type RouteMapPoint } from '@/components/trip/RouteMap'
import { LivePositionSummary } from '@/features/trips/LiveTrackingCard'
import { useNow } from '@/hooks/useNow'
import { usePublicLive } from '@/hooks/useTrips'
import { describeError, isDefinitiveError } from '@/lib/errors'
import { BENIN_TIME_HINT, deviceClockDiffersFromBenin, formatDateTime } from '@/lib/format'
import { liveAgeSeconds, toVehicle } from '@/lib/liveTracking'

/**
 * Suivi public par jeton (/live/{token}, V23) : un proche du passager suit le vehicule
 * sans compte. Le serveur ne livre que le prenom du conducteur, le vehicule, l axe,
 * l heure de depart et la derniere position ; le lien meurt avec le partage ou 6 h apres
 * la fin du trajet. Jamais indexe.
 */
export function LiveTrackingPage() {
  const { token } = useParams<{ token: string }>()
  const live = usePublicLive(token)
  const data = live.data
  const running = !!data && data.tripStatus !== 'COMPLETED' && data.tripStatus !== 'CANCELLED'
  const now = useNow(running)
  const clockDiffers = deviceClockDiffersFromBenin()

  const points = useMemo<RouteMapPoint[]>(() => {
    if (!data) return []
    return [
      { label: data.originLabel, lat: data.originLat, lng: data.originLng, kind: 'origin' },
      { label: data.destLabel, lat: data.destLat, lng: data.destLng, kind: 'destination' },
    ]
  }, [data])

  if (isOfflineWithoutData(live)) {
    return (
      <PageContainer width="md">
        <PageMeta title="Suivi en direct" noindex />
        <OfflineState headingLevel="h1" description="Le suivi s’affichera dès que la connexion reviendra." onRetry={() => live.refetch()} />
      </PageContainer>
    )
  }
  if (live.isPending) {
    return (
      <PageContainer width="md">
        <PageMeta title="Suivi en direct" noindex />
        <Skeleton className="mb-4 h-9 w-2/3" />
        <Skeleton className="mb-3 h-[220px] rounded-[var(--radius-card)]" />
        <Card className="space-y-3 p-4">
          <Skeleton className="h-4 w-40" />
          <Skeleton className="h-4 w-full" />
        </Card>
      </PageContainer>
    )
  }
  if (live.isError || !data) {
    const final = isDefinitiveError(live.error)
    return (
      <PageContainer width="md">
        <PageMeta title="Suivi terminé" noindex />
        <ErrorState
          headingLevel="h1"
          title={final ? 'Suivi terminé ou lien expiré' : 'Chargement impossible'}
          description={
            final
              ? 'Le conducteur a arrêté le partage de sa position, ou ce trajet est terminé depuis plusieurs heures.'
              : describeError(live.error)
          }
          onRetry={final ? undefined : () => live.refetch()}
        />
        <div className="mt-2 flex justify-center">
          <Button asChild variant="secondary">
            <Link to="/">Découvrir Ekuiseo</Link>
          </Button>
        </div>
      </PageContainer>
    )
  }

  const age = liveAgeSeconds(data.staleSeconds, live.dataUpdatedAt, now)
  const vehicle = toVehicle(data.position, age)
  const finished = !running

  return (
    <PageContainer width="md">
      <PageMeta
        title={`Suivi en direct · ${data.originLabel} → ${data.destLabel}`}
        description="Position du véhicule partagée par le conducteur pendant le trajet."
        noindex
      />
      <PageHeader
        title={`${data.originLabel} → ${data.destLabel}`}
        subtitle={`Départ ${formatDateTime(data.departureAt)}${clockDiffers ? ` (${BENIN_TIME_HINT})` : ''}`}
        actions={
          <Badge tone={finished ? 'neutral' : 'success'}>
            <Navigation aria-hidden />
            {finished ? 'Trajet terminé' : 'En direct'}
          </Badge>
        }
      />

      <div className="space-y-4">
        <RouteMap points={points} className="h-[240px] sm:h-[320px]" activation="on-demand" vehicle={vehicle} />

        <Card className="p-4 sm:p-5">
          <LivePositionSummary
            position={data.position}
            age={age}
            destination={{ lat: data.destLat, lng: data.destLng, label: data.destLabel }}
            tripStatus={data.tripStatus}
          />
        </Card>

        <Card className="flex items-center gap-3 p-4">
          <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-primary-soft text-primary-ink" aria-hidden>
            <Car className="size-5" />
          </span>
          <div className="min-w-0">
            <p className="font-display text-title font-bold">{data.driverFirstName} conduit</p>
            <p className="text-label text-muted">
              {data.vehicle
                ? `${data.vehicle.brand} ${data.vehicle.model}${data.vehicle.color ? ` · ${data.vehicle.color}` : ''}`
                : 'Véhicule non précisé'}
            </p>
          </div>
        </Card>

        <p className="text-caption leading-relaxed text-muted">
          Ce lien a été partagé par un passager ou le conducteur. Il cesse de fonctionner dès que le conducteur arrête le
          partage, et au plus tard quelques heures après l’arrivée. Distance et heure d’arrivée sont des estimations.
        </p>
      </div>
    </PageContainer>
  )
}
