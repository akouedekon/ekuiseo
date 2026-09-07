import { m } from 'motion/react'
import {
  BadgeCheck,
  Briefcase,
  ChevronRight,
  Cigarette,
  Dog,
  Music,
  Flag,
  Snowflake,
  Users,
} from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { ReportDialog } from '@/components/feedback/ReportDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Avatar, RatingStars, Separator, Skeleton } from '@/components/ui/misc'
import { ErrorState, OfflineState, isOfflineWithoutData } from '@/components/ui/states'
import { PageContainer, PageHeader, SectionTitle } from '@/components/layout/PageContainer'
import { RouteMap } from '@/components/trip/RouteMap'
import { RouteTimeline } from '@/components/trip/RouteTimeline'
import { ShareTripButton } from '@/components/trip/ShareTripButton'
import { buildRoutePoints } from '@/lib/route'
import { estimatePaymentPlan } from '@/lib/payments'
import { useIsAuthenticated, useMe } from '@/hooks/useAuth'
import { usePublicUser, useUserReviews } from '@/hooks/useReviews'
import { useTrip, useTripStops } from '@/hooks/useTrips'
import { format, parseISO } from 'date-fns'
import { useIsDesktop } from '@/hooks/useMediaQuery'
import { estimateArrivalIso } from '@/lib/cities'
import { formatDuration, formatFcfa, formatFromNow, formatRelativeDay } from '@/lib/format'

export function TripDetailPage() {
  const { id } = useParams<{ id: string }>()
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

  if (isOfflineWithoutData(trip))
    return (
      <PageContainer width="md">
        <OfflineState
          description="Ce trajet n'a pas encore été enregistré sur cet appareil. Il s'affichera dès que la connexion reviendra."
          onRetry={() => trip.refetch()}
        />
      </PageContainer>
    )
  if (trip.isPending) return <TripDetailSkeleton />
  if (trip.isError || !trip.data)
    return (
      <PageContainer width="md">
        <ErrorState
          title="Trajet introuvable"
          description="Ce trajet a peut-être été retiré. Revenez aux résultats de recherche."
          onRetry={() => trip.refetch()}
        />
      </PageContainer>
    )

  const data = trip.data
  // Arrivee et duree sont des ESTIMATIONS du front (modele a deux vitesses, lib/cities.ts).
  const arrival = estimateArrivalIso(data)
  const durationMin = Math.round((new Date(arrival).getTime() - new Date(data.departureAt).getTime()) / 60_000)
  const stopList = stops.data ?? []
  /*
   * Retour depuis un lien partage (WhatsApp) : pas d'historique dans l'application.
   * On reconstruit la recherche correspondante plutot que d'envoyer vers /search sans
   * parametres, soit l'ecran « Recherche incomplete » (audit F220).
   */
  const backTo = `/search?${new URLSearchParams({
    from: data.originLabel,
    fromLat: String(data.originLat),
    fromLng: String(data.originLng),
    to: data.destLabel,
    toLat: String(data.destLat),
    toLng: String(data.destLng),
    date: format(parseISO(data.departureAt), 'yyyy-MM-dd'),
    seats: '1',
    type: data.tripType,
  }).toString()}`
  const points = buildRoutePoints(
    data.originLabel,
    data.destLabel,
    data.departureAt,
    arrival,
    data.pricePerSeat,
    stopList,
  )
  const mapPoints = [
    { label: data.originLabel, lat: data.originLat, lng: data.originLng, kind: 'origin' as const },
    ...stopList.map((s) => ({ label: s.label, lat: s.lat, lng: s.lng, kind: 'stop' as const })),
    { label: data.destLabel, lat: data.destLat, lng: data.destLng, kind: 'destination' as const },
  ]
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
  const reviewList = (reviews.data ?? []).slice(0, 4)
  const shareText = `${data.originLabel} → ${data.destLabel}, ${formatRelativeDay(data.departureAt).toLowerCase()} — ${formatFcfa(data.pricePerSeat)} par place sur Ekuiseo`
  const primaryLabel = isOwnTrip
    ? 'Votre trajet'
    : cancelled
      ? 'Trajet annulé'
      : departed
        ? 'Trajet déjà parti'
        : full
          ? 'Complet'
          : 'Réserver'

  return (
    <>
      <PageContainer width="lg" className="pb-32 md:pb-10">

        <PageHeader
          title={`${data.originLabel} → ${data.destLabel}`}
          subtitle={`${formatRelativeDay(data.departureAt)} · ≈ ${formatDuration(durationMin)} de route (estimation)`}
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
              <Card className="border-[var(--vermillon)] bg-[var(--vermillon-soft)] p-4 text-[14px] font-medium text-[var(--vermillon)]">
                Ce trajet a été annulé par le conducteur.
              </Card>
            ) : departed && !isOwnTrip ? (
              <Card className="border-[var(--ocre)] bg-[var(--ocre-soft)] p-4 text-[14px] font-medium text-[var(--ocre-deep,var(--ocre))]">
                Ce trajet est déjà parti. Cherchez un prochain départ sur le même axe.
              </Card>
            ) : null}

            {/* --- Itineraire et tarif par troncon --- */}
            <Card className="p-4 sm:p-5">
              <SectionTitle
                action={
                  stopList.length > 0 ? (
                    <span className="text-[12px] text-muted">Prix depuis {data.originLabel}</span>
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
                  <p className="mt-3 flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-control)] bg-[var(--ocre-soft)] px-3 py-2 text-[13px] text-[var(--ocre-ink)]">
                    Arrêts intermédiaires indisponibles pour l'instant.
                    <button type="button" className="font-semibold underline-offset-4 hover:underline" onClick={() => stops.refetch()}>
                      Réessayer
                    </button>
                  </p>
                </>
              ) : (
                <RouteTimeline points={points} />
              )}

              {stopList.length > 0 ? (
                <p className="mt-3 rounded-[var(--radius-control)] bg-[var(--surface-calm)] px-3 py-2 text-[13px] text-ink-2">
                  Vous pouvez descendre à un arrêt intermédiaire : le prix du tronçon s'applique automatiquement à la
                  réservation.
                </p>
              ) : null}
            </Card>

            {/* Une seule instance de carte a la fois : mobile ici, desktop dans la colonne laterale. */}
            {!desktop ? <RouteMap points={mapPoints} className="h-[220px]" /> : null}

            {/* --- Conducteur --- */}
            <Card>
              <Link
                to={`/drivers/${data.driver.id}`}
                className="flex items-center gap-3 p-4 transition-colors hover:bg-[var(--surface-calm)]"
              >
                <Avatar
                  firstName={data.driver.firstName}
                  lastName={data.driver.lastName}
                  photoUrl={data.driver.photoUrl}
                  size={52}
                />
                <div className="min-w-0 flex-1">
                  <p className="truncate font-display text-[17px] font-bold">
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
                  <p className="px-4 py-3 text-[14px] leading-relaxed text-ink-2">{data.description}</p>
                </>
              ) : null}
            </Card>

            {/* --- Vehicule et conditions --- */}
            <Card className="p-4 sm:p-5">
              <SectionTitle>Véhicule et conditions</SectionTitle>
              <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
                <div>
                  <p className="font-display text-[15px] font-bold">
                    {data.vehicle.brand} {data.vehicle.model}
                  </p>
                  <p className="text-[13px] text-muted">
                    {data.vehicle.color ?? 'Couleur non précisée'} ·{' '}
                    {
                      { BASIC: 'Confort simple', COMFORT: 'Confortable', PREMIUM: 'Haut de gamme' }[
                        data.vehicle.comfortLevel
                      ]
                    }
                  </p>
                </div>
                <div className="ml-auto flex flex-wrap gap-1.5">
                  <Badge tone={full ? 'danger' : 'neutral'}>
                    <Users aria-hidden />
                    {full ? 'Complet' : `${data.seatsAvailable}/${data.seatsTotal} places`}
                  </Badge>
                  {data.vehicle.comfortLevel !== 'BASIC' ? (
                    <Badge tone="neutral">
                      <Snowflake aria-hidden />
                      Climatisé
                    </Badge>
                  ) : null}
                </div>
              </div>

              {data.luggagePolicy ? (
                <p className="mt-3 flex items-start gap-2 text-[14px] text-ink-2">
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
                    className="text-[13px] font-medium text-[var(--indigo)] underline-offset-4 hover:underline"
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
                <Card className="flex flex-wrap items-center justify-between gap-2 p-4 text-[14px] text-muted">
                  Avis indisponibles pour l'instant.
                  <Button variant="secondary" size="sm" onClick={() => reviews.refetch()}>
                    Réessayer
                  </Button>
                </Card>
              ) : reviewList.length === 0 ? (
                <Card className="p-4 text-[14px] text-muted">Aucun avis pour l'instant.</Card>
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
                          <span className="shrink-0 text-[12px] text-muted">{formatFromNow(review.createdAt)}</span>
                        </div>
                        {review.comment ? (
                          <p className="mt-1.5 text-[14px] leading-relaxed text-ink-2">{review.comment}</p>
                        ) : null}
                      </Card>
                    </m.div>
                  ))}
                </div>
              )}
            </section>

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
                    <p className="mt-2 text-center text-[12px] text-muted">
                      Vous conduisez ce trajet. Partagez le lien pour remplir les places.
                    </p>
                  </>
                ) : (
                  <>
                    <Button
                      size="lg"
                      block
                      className="mt-4"
                      disabled={!bookable}
                      onClick={() => navigate(`/book/${data.id}`)}
                    >
                      {primaryLabel}
                    </Button>
                    <p className="mt-2 text-center text-[12px] text-muted">
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

      {/* --- Barre d'action collante (mobile) --- */}
      <div className="ek-glass safe-bottom fixed inset-x-0 bottom-[68px] z-30 border-t border-rule px-4 py-3 lg:hidden">
        <div className="mx-auto flex max-w-3xl items-center gap-2">
          <div className="min-w-0">
            <p className="tnum font-display text-[22px] font-extrabold leading-none tracking-[-0.03em]">
              {formatFcfa(data.pricePerSeat)}
            </p>
            <p className="text-[12px] text-muted">par place</p>
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
              onClick={() => navigate(`/book/${data.id}`)}
            >
              {cancelled ? 'Annulé' : departed ? 'Déjà parti' : full ? 'Complet' : 'Réserver'}
            </Button>
          )}
        </div>
      </div>
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
    <dl className="space-y-2 text-[14px]">
      <div className="flex items-baseline justify-between">
        <dt className="text-muted">Prix par place</dt>
        <dd className="tnum font-display text-[22px] font-extrabold tracking-[-0.03em]">{formatFcfa(pricePerSeat)}</dd>
      </div>
      <Separator />
      <div className="flex items-baseline justify-between">
        <dt className="text-muted">Acompte en ligne</dt>
        <dd className="tnum font-semibold">≈ {formatFcfa(estimate.depositAmount)}</dd>
      </div>
      <div className="flex items-baseline justify-between">
        <dt className="text-muted">Solde en espèces</dt>
        <dd className="tnum font-semibold">≈ {formatFcfa(estimate.balanceAmount)}</dd>
      </div>
      <p className="pt-1 text-[12px] leading-snug text-muted">
        Estimation pour une place. Le montant exact est confirmé à l'étape de réservation.
      </p>
    </dl>
  )
}

function TripDetailSkeleton() {
  return (
    <PageContainer width="lg">
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
