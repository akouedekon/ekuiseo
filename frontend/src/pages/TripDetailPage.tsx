import { m } from 'motion/react'
import {
  ArrowLeftRight,
  BadgeCheck,
  Briefcase,
  ChevronRight,
  Cigarette,
  Dog,
  Music,
  Flag,
  Snowflake,
  UserCheck,
  Users,
} from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { ReportDialog } from '@/components/feedback/ReportDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Avatar, RatingStars, Separator, Skeleton } from '@/components/ui/misc'
import { ErrorState, OfflineState, isOfflineWithoutData } from '@/components/ui/states'
import { PageContainer, PageHeader, SectionTitle } from '@/components/layout/PageContainer'
import { PageMeta } from '@/components/layout/PageMeta'
import { StickyActionBar, stickyClearanceClass } from '@/components/layout/StickyActionBar'
import { RouteMap, type RouteMapPoint } from '@/components/trip/RouteMap'
import { VehicleTypeBadge, VehicleTypeIcon } from '@/components/trip/VehicleTypeIcon'
import { RouteTimeline } from '@/components/trip/RouteTimeline'
import { ShareTripButton } from '@/components/trip/ShareTripButton'
import { buildRoutePoints, estimateArrival } from '@/lib/route'
import { estimatePaymentPlan } from '@/lib/payments'
import { useIsAuthenticated, useMe } from '@/hooks/useAuth'
import { usePublicUser, useUserReviews } from '@/hooks/useReviews'
import { useTrip, useTripStops } from '@/hooks/useTrips'
import { useIsDesktop } from '@/hooks/useMediaQuery'
import { describeError, isDefinitiveError } from '@/lib/errors'
import {
  BENIN_TIME_HINT,
  deviceClockDiffersFromBenin,
  formatDuration,
  formatFcfa,
  formatFromNow,
  formatRelativeDay,
  toInputDate,
} from '@/lib/format'
import { COMFORT_LABEL, DRIVER_APPROVAL_BADGE } from '@/lib/labels'
import type { TripResponse } from '@/api/types'

/** Recherche equivalente a un trajet (retour depuis un lien partage) ou son inverse (« Trajet retour »). */
function searchPath(trip: TripResponse, seats: number, reverse = false): string {
  const from = reverse
    ? { label: trip.destLabel, lat: trip.destLat, lng: trip.destLng }
    : { label: trip.originLabel, lat: trip.originLat, lng: trip.originLng }
  const to = reverse
    ? { label: trip.originLabel, lat: trip.originLat, lng: trip.originLng }
    : { label: trip.destLabel, lat: trip.destLat, lng: trip.destLng }
  const params = new URLSearchParams({
    from: from.label,
    fromLat: String(from.lat),
    fromLng: String(from.lng),
    to: to.label,
    toLat: String(to.lat),
    toLng: String(to.lng),
    seats: String(seats),
    type: trip.tripType,
  })
  // Le retour se cherche a partir du jour du depart, jamais dans le passe.
  const departureDay = toInputDate(trip.departureAt)
  params.set('date', reverse ? (departureDay < toInputDate(new Date()) ? toInputDate(new Date()) : departureDay) : departureDay)
  return `/search?${params.toString()}`
}

export function TripDetailPage() {
  const { id } = useParams<{ id: string }>()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const trip = useTrip(id)
  const stops = useTripStops(id)
  const me = useMe()
  const authed = useIsAuthenticated()
  const [reportOpen, setReportOpen] = useState(false)
  const desktop = useIsDesktop()
  const driverId = trip.data?.driver.id
  const driver = usePublicUser(driverId)
  const reviews = useUserReviews(driverId)
  // Places demandees a la recherche, propagees jusqu'a la reservation (audit F249).
  const requestedSeats = Math.max(1, Math.min(8, Number(searchParams.get('seats')) || 1))
  const clockDiffers = deviceClockDiffersFromBenin()

  const data = trip.data
  const stopList = stops.data
  /* Points de la carte : une nouvelle reference a chaque rendu recreerait la carte MapLibre (audit F241). */
  const mapPoints = useMemo<RouteMapPoint[]>(() => {
    if (!data) return []
    return [
      { label: data.originLabel, lat: data.originLat, lng: data.originLng, kind: 'origin' },
      ...(stopList ?? []).map((s) => ({ label: s.label, lat: s.lat, lng: s.lng, kind: 'stop' as const })),
      { label: data.destLabel, lat: data.destLat, lng: data.destLng, kind: 'destination' },
    ]
  }, [data, stopList])

  if (isOfflineWithoutData(trip))
    return (
      <PageContainer width="md">
        <PageMeta title="Trajet" />
        <OfflineState
          headingLevel="h1"
          description="Ce trajet n'a pas encore été enregistré sur cet appareil. Il s'affichera dès que la connexion reviendra."
          onRetry={() => trip.refetch()}
        />
      </PageContainer>
    )
  if (trip.isPending) return <TripDetailSkeleton />
  if (trip.isError || !data) {
    const final = isDefinitiveError(trip.error)
    return (
      <PageContainer width="md">
        <PageMeta title="Trajet introuvable" noindex />
        <ErrorState
          headingLevel="h1"
          title={final ? 'Trajet introuvable' : 'Chargement impossible'}
          description={final ? 'Ce trajet a été retiré ou n’existe pas. Revenez aux résultats de recherche.' : describeError(trip.error)}
          onRetry={final ? undefined : () => trip.refetch()}
        />
        {final ? (
          <div className="mt-2 flex justify-center">
            <Button asChild variant="secondary">
              <Link to="/">Nouvelle recherche</Link>
            </Button>
          </div>
        ) : null}
      </PageContainer>
    )
  }

  // Arrivee et duree sont des ESTIMATIONS du front (modele a deux vitesses, lib/route.ts).
  const { arrivalIso, durationMinutes } = estimateArrival(data)
  const stopsShown = stopList ?? []
  /*
   * Retour depuis un lien partage (WhatsApp) : pas d'historique dans l'application.
   * On reconstruit la recherche correspondante plutot que d'envoyer vers /search sans
   * parametres, soit l'ecran « Recherche incomplete » (audit F220).
   */
  const backTo = searchPath(data, requestedSeats)
  const points = buildRoutePoints(data.originLabel, data.destLabel, data.departureAt, arrivalIso, data.pricePerSeat, stopsShown)
  const full = data.seatsAvailable === 0
  const cancelled = data.status === 'CANCELLED'
  // Un trajet parti (statut serveur ou heure locale depassee) ne se reserve plus (constat F035).
  const departed =
    data.status === 'ONGOING' ||
    data.status === 'COMPLETED' ||
    (data.status !== 'TEMPLATE' && new Date(data.departureAt).getTime() < Date.now())
  const bookable = !full && !cancelled && !departed && data.status === 'PUBLISHED'
  // Regle metier n.8 : un conducteur ne reserve pas sur son propre trajet.
  const isOwnTrip = me.data?.id === data.driver.id
  const driverData = driver.data
  const reviewList = (reviews.data ?? []).filter((review) => review.role === 'DRIVER').slice(0, 4)
  const shareText = `${data.originLabel} → ${data.destLabel}, ${formatRelativeDay(data.departureAt).toLowerCase()} — ${formatFcfa(data.pricePerSeat)} par place sur Ekuiseo`
  const bookHref = `/book/${data.id}${requestedSeats > 1 ? `?seats=${requestedSeats}` : ''}`
  const primaryLabel = isOwnTrip
    ? 'Votre trajet'
    : cancelled
      ? 'Trajet annulé'
      : departed
        ? 'Trajet déjà parti'
        : full
          ? 'Complet'
          : 'Réserver'
  const subtitle = `${formatRelativeDay(data.departureAt)} · ≈ ${formatDuration(durationMinutes)} de route (estimation)${
    clockDiffers ? ` · ${BENIN_TIME_HINT}` : ''
  }`

  return (
    <>
      <PageMeta
        title={`${data.originLabel} → ${data.destLabel} · ${formatRelativeDay(data.departureAt)}`}
        description={`${formatFcfa(data.pricePerSeat)} par place, ${data.seatsAvailable} place${data.seatsAvailable > 1 ? 's' : ''} disponible${data.seatsAvailable > 1 ? 's' : ''}. Acompte en mobile money, solde en espèces à bord.`}
      />
      {/* Sous `lg`, la barre d'action et la navigation basse recouvrent le bas de page : on reserve leur hauteur (audit L8). */}
      <PageContainer width="lg" className={stickyClearanceClass}>
        <PageHeader
          title={`${data.originLabel} → ${data.destLabel}`}
          subtitle={subtitle}
          backTo={backTo}
          actions={
            <ShareTripButton
              title={`${data.originLabel} → ${data.destLabel}`}
              text={shareText}
              path={`/trips/${data.id}`}
              className="hidden sm:inline-flex"
            />
          }
        />

        <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_340px]">
          <div className="space-y-4">
            {cancelled ? (
              <Card className="border-danger bg-danger-soft p-4 text-body font-medium text-danger-ink" role="status">
                Ce trajet a été annulé par le conducteur.
              </Card>
            ) : departed && !isOwnTrip ? (
              <Card className="border-accent bg-accent-soft p-4 text-body font-medium text-accent-ink" role="status">
                Ce trajet est déjà parti. Cherchez un prochain départ sur le même axe.
              </Card>
            ) : null}

            {/* --- Itineraire et tarif par troncon --- */}
            <Card className="p-4 sm:p-5">
              <SectionTitle
                action={
                  stopsShown.length > 0 ? (
                    <span className="text-caption text-muted">Prix depuis {data.originLabel}</span>
                  ) : null
                }
              >
                Itinéraire
              </SectionTitle>
              {stops.isPending ? (
                <div className="space-y-3">
                  <Skeleton className="h-5 w-2/3" />
                  <Skeleton className="h-5 w-1/2" />
                </div>
              ) : stops.isError ? (
                <>
                  <RouteTimeline points={points} />
                  <p className="mt-3 flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-control)] bg-accent-soft px-3 py-2 text-label text-accent-ink">
                    Arrêts intermédiaires indisponibles pour l'instant.
                    <button type="button" className="font-semibold underline-offset-4 hover:underline" onClick={() => stops.refetch()}>
                      Réessayer
                    </button>
                  </p>
                </>
              ) : (
                <RouteTimeline points={points} />
              )}

              {stopsShown.length > 0 ? (
                <p className="mt-3 rounded-[var(--radius-control)] bg-surface-2 px-3 py-2 text-label text-ink-2">
                  Vous pouvez descendre à un arrêt intermédiaire : le prix du tronçon s'applique automatiquement à la
                  réservation.
                </p>
              ) : null}
            </Card>

            {/* Une seule instance de carte a la fois : mobile ici (plus basse, activee a la demande), desktop dans la colonne laterale. */}
            {!desktop ? <RouteMap points={mapPoints} className="h-[180px]" activation="on-demand" /> : null}

            {/* --- Conducteur --- */}
            <Card>
              <Link
                to={`/drivers/${data.driver.id}`}
                className="flex items-center gap-3 p-4 transition-colors hover:bg-surface-2"
              >
                <Avatar
                  firstName={data.driver.firstName}
                  lastName={data.driver.lastName}
                  photoUrl={data.driver.photoUrl}
                  size={52}
                />
                <div className="min-w-0 flex-1">
                  <p className="truncate font-display text-title font-bold">
                    {data.driver.firstName} {data.driver.lastName}
                  </p>
                  <RatingStars value={data.driver.ratingAvg} count={data.driver.ratingCount} className="mt-0.5" />
                  {data.driver.identityVerified ? (
                    <Badge tone="success" className="mt-1.5">
                      <BadgeCheck aria-hidden />
                      Identité vérifiée
                    </Badge>
                  ) : null}
                </div>
                <ChevronRight className="size-5 shrink-0 text-muted" aria-hidden />
              </Link>

              {data.description ? (
                <>
                  <Separator />
                  <p className="px-4 py-3 text-body leading-relaxed text-ink-2">{data.description}</p>
                </>
              ) : null}
            </Card>

            {/* --- Vehicule et conditions --- */}
            <Card className="p-4 sm:p-5">
              <SectionTitle>Véhicule et conditions</SectionTitle>
              <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
                <div className="flex items-center gap-3">
                  <span className="flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-surface-2 text-ink-2">
                    <VehicleTypeIcon type={data.vehicle.vehicleType} className="size-5" />
                  </span>
                  <div>
                    <p className="font-display text-base font-bold">
                      {data.vehicle.brand} {data.vehicle.model}
                    </p>
                    <p className="text-label text-muted">
                      {data.vehicle.color ?? 'Couleur non précisée'} · {COMFORT_LABEL[data.vehicle.comfortLevel]}
                    </p>
                  </div>
                </div>
                <div className="ml-auto flex flex-wrap gap-1.5">
                  <VehicleTypeBadge type={data.vehicle.vehicleType} />
                  <Badge tone={full ? 'danger' : 'neutral'}>
                    <Users aria-hidden />
                    {full ? 'Complet' : `${data.seatsAvailable}/${data.seatsTotal} places`}
                  </Badge>
                  {!data.instantBooking ? (
                    <Badge tone="outline" title="Le conducteur accepte chaque passager avant confirmation">
                      <UserCheck aria-hidden />
                      {DRIVER_APPROVAL_BADGE}
                    </Badge>
                  ) : null}
                  {data.vehicle.comfortLevel !== 'BASIC' ? (
                    <Badge tone="neutral">
                      <Snowflake aria-hidden />
                      Climatisé
                    </Badge>
                  ) : null}
                </div>
              </div>
              {data.vehicle.vehicleType === 'MOTO' ? (
                <p className="mt-3 rounded-[var(--radius-control)] bg-accent-soft px-3 py-2 text-label text-accent-ink">
                  Trajet à moto : un seul passager, casque obligatoire pour les deux, fourni par le conducteur.
                </p>
              ) : null}

              {data.luggagePolicy ? (
                <p className="mt-3 flex items-start gap-2 text-body text-ink-2">
                  <Briefcase className="mt-0.5 size-4 shrink-0 text-muted" aria-hidden />
                  {data.luggagePolicy}
                </p>
              ) : null}

              {driverData?.preferences ? (
                <div className="mt-3 flex flex-wrap gap-1.5">
                  <Badge tone={driverData.preferences.smoking ? 'neutral' : 'outline'}>
                    <Cigarette aria-hidden />
                    {driverData.preferences.smoking ? 'Fumeur accepté' : 'Non-fumeur'}
                  </Badge>
                  <Badge tone={driverData.preferences.music ? 'neutral' : 'outline'}>
                    <Music aria-hidden />
                    {driverData.preferences.music ? 'Musique' : 'Sans musique'}
                  </Badge>
                  <Badge tone={driverData.preferences.pets ? 'neutral' : 'outline'}>
                    <Dog aria-hidden />
                    {driverData.preferences.pets ? 'Animaux acceptés' : 'Sans animaux'}
                  </Badge>
                </div>
              ) : null}
            </Card>

            {/* --- Avis --- */}
            <section>
              <SectionTitle
                action={
                  <Link
                    to={`/drivers/${data.driver.id}`}
                    className="text-label font-medium text-primary-ink underline-offset-4 hover:underline"
                  >
                    Tout voir
                  </Link>
                }
              >
                Avis sur le conducteur
              </SectionTitle>
              {reviews.isPending ? (
                <Card className="space-y-2 p-4">
                  <Skeleton className="h-4 w-32" />
                  <Skeleton className="h-4 w-full" />
                </Card>
              ) : reviews.isError ? (
                <Card className="flex flex-wrap items-center justify-between gap-2 p-4 text-body text-muted">
                  Avis indisponibles pour l'instant.
                  <Button variant="secondary" size="sm" onClick={() => reviews.refetch()}>
                    Réessayer
                  </Button>
                </Card>
              ) : reviewList.length === 0 ? (
                <Card className="p-4 text-body text-muted">Aucun avis pour l'instant.</Card>
              ) : (
                <div className="space-y-2">
                  {reviewList.map((review, index) => (
                    <m.div
                      key={review.id}
                      initial={{ opacity: 0, y: 8 }}
                      animate={{ opacity: 1, y: 0 }}
                      transition={{ delay: index * 0.05 }}
                    >
                      <Card className="p-4">
                        <div className="flex items-center justify-between gap-3">
                          <RatingStars value={review.rating} size={13} />
                          <span className="shrink-0 text-caption text-muted">{formatFromNow(review.createdAt)}</span>
                        </div>
                        {review.comment ? (
                          <p className="mt-1.5 text-body leading-relaxed text-ink-2">{review.comment}</p>
                        ) : null}
                      </Card>
                    </m.div>
                  ))}
                </div>
              )}
            </section>

            {/* Trajet retour : la meme recherche, inversee (audit, section 5 #21). */}
            <Card className="flex flex-col items-start gap-3 p-4 sm:flex-row sm:items-center">
              <ArrowLeftRight className="size-5 shrink-0 text-primary-ink" aria-hidden />
              <p className="flex-1 text-body text-ink-2">
                Besoin du retour ? Cherchez un départ de {data.destLabel} vers {data.originLabel}.
              </p>
              <Button asChild variant="secondary" size="sm">
                <Link to={searchPath(data, requestedSeats, true)}>Trajet retour</Link>
              </Button>
            </Card>

            {authed && !isOwnTrip ? (
              <div className="flex justify-end">
                <Button variant="ghost" size="sm" className="text-muted" onClick={() => setReportOpen(true)}>
                  <Flag className="size-4" aria-hidden />
                  Signaler ce trajet
                </Button>
              </div>
            ) : null}
          </div>

          {/* --- Colonne de reservation (desktop) --- */}
          <aside className="hidden lg:block">
            <div className="sticky top-24 space-y-3">
              {desktop ? <RouteMap points={mapPoints} className="h-[240px]" /> : null}
              <Card className="p-4">
                <PriceBlock pricePerSeat={data.pricePerSeat} />
                {isOwnTrip ? (
                  <>
                    <Button asChild variant="secondary" size="lg" block className="mt-4">
                      <Link to="/trips/mine">Gérer mes trajets</Link>
                    </Button>
                    <p className="mt-2 text-center text-caption text-muted">
                      Vous conduisez ce trajet. Partagez le lien pour remplir les places.
                    </p>
                  </>
                ) : (
                  <>
                    <Button size="lg" block className="mt-4" disabled={!bookable} onClick={() => navigate(bookHref)}>
                      {primaryLabel}
                    </Button>
                    <p className="mt-2 text-center text-caption text-muted">
                      Acompte en ligne, solde en espèces à bord — ou paiement intégral si vous préférez.
                    </p>
                  </>
                )}
              </Card>
            </div>
          </aside>
        </div>
      </PageContainer>

      <ReportDialog
        open={reportOpen}
        onOpenChange={setReportOpen}
        target={{ tripId: data.id, label: `le trajet ${data.originLabel} → ${data.destLabel}` }}
      />

      {/* --- Barre d'action collante (mobile), posee au-dessus de la navigation basse et de la zone sure (audit F325) --- */}
      <StickyActionBar>
        <div className="min-w-0">
          <p className="tnum font-display text-display font-extrabold leading-none tracking-[-0.03em]">
            {formatFcfa(data.pricePerSeat)}
          </p>
          <p className="text-caption text-muted">par place</p>
        </div>
        <ShareTripButton
          title={`${data.originLabel} → ${data.destLabel}`}
          text={shareText}
          path={`/trips/${data.id}`}
          size="lg"
          iconOnly
          className="ml-auto sm:hidden"
        />
        {isOwnTrip ? (
          <Button asChild variant="secondary" size="lg" className="flex-1 sm:ml-auto sm:flex-none sm:px-10">
            <Link to="/trips/mine">Gérer mes trajets</Link>
          </Button>
        ) : (
          <Button
            size="lg"
            className="flex-1 sm:ml-auto sm:flex-none sm:px-10"
            disabled={!bookable}
            onClick={() => navigate(bookHref)}
          >
            {cancelled ? 'Annulé' : departed ? 'Déjà parti' : full ? 'Complet' : 'Réserver'}
          </Button>
        )}
      </StickyActionBar>
    </>
  )
}

/**
 * Ventilation indicative avant reservation. Les montants sont ESTIMES avec la
 * meme regle que le serveur (acompte = max(plancher, frais de service)) et
 * annonces comme tels : le decompte ferme arrive avec le devis de reservation.
 */
function PriceBlock({ pricePerSeat }: { pricePerSeat: number }) {
  const estimate = estimatePaymentPlan(pricePerSeat, 'MOMO_DEPOSIT')
  return (
    <dl className="space-y-2 text-body">
      <div className="flex items-baseline justify-between">
        <dt className="text-muted">Prix par place</dt>
        <dd className="tnum font-display text-display font-extrabold tracking-[-0.03em]">{formatFcfa(pricePerSeat)}</dd>
      </div>
      <Separator />
      <div className="flex items-baseline justify-between">
        <dt className="text-muted">Acompte en ligne</dt>
        <dd className="tnum font-semibold" title="Estimation">
          ≈ {formatFcfa(estimate.depositAmount)}
        </dd>
      </div>
      <div className="flex items-baseline justify-between">
        <dt className="text-muted">Solde en espèces</dt>
        <dd className="tnum font-semibold" title="Estimation">
          ≈ {formatFcfa(estimate.balanceAmount)}
        </dd>
      </div>
      <p className="pt-1 text-caption leading-snug text-muted">
        Estimation pour une place. Le montant exact est confirmé à l'étape de réservation.
      </p>
    </dl>
  )
}

function TripDetailSkeleton() {
  return (
    <PageContainer width="lg">
      <PageMeta title="Trajet" />
      <Skeleton className="mb-4 h-9 w-2/3" />
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_340px]">
        <div className="space-y-4">
          <Card className="space-y-3 p-5">
            <Skeleton className="h-3 w-24" />
            <Skeleton className="h-5 w-3/4" />
            <Skeleton className="h-5 w-2/3" />
            <Skeleton className="h-5 w-1/2" />
          </Card>
          <Card className="flex items-center gap-3 p-4">
            <Skeleton className="size-13 rounded-full" />
            <div className="flex-1 space-y-2">
              <Skeleton className="h-4 w-40" />
              <Skeleton className="h-3 w-24" />
            </div>
          </Card>
        </div>
        <Skeleton className="hidden h-[320px] rounded-[var(--radius-card)] lg:block" />
      </div>
    </PageContainer>
  )
}
