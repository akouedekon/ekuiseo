import { m } from 'motion/react'
import { ArrowUpDown, BellPlus, CircleDot, Flag, SearchX, SlidersHorizontal, Star, WifiOff, X } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/misc'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Sheet } from '@/components/ui/sheet'
import { Slider } from '@/components/ui/misc'
import { SegmentedToggle } from '@/components/ui/tabs'
import { EmptyState, ErrorState, ListSkeleton, OfflineState, SlowNetworkNotice, isOfflineWithoutData } from '@/components/ui/states'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageMeta } from '@/components/layout/PageMeta'
import { RouteMap } from '@/components/trip/RouteMap'
import { TripCard } from '@/components/trip/TripCard'
import { useCreateTripAlert } from '@/hooks/useAlerts'
import { useIsAuthenticated } from '@/hooks/useAuth'
import { useIsDesktop } from '@/hooks/useMediaQuery'
import { useOnlineStatus, useSlow, useStaleAge } from '@/hooks/useNetwork'
import { useTripSearchPages, type TripSearchParams, type TripSearchSort } from '@/hooks/useTrips'
import { describeError, errorStatus, isDefinitiveError } from '@/lib/errors'
import { haversineKm, searchRadiusKm, type PlaceKind } from '@/lib/cities'
import { BENIN_TIME_HINT, deviceClockDiffersFromBenin, formatDayShort, formatFcfa, toInputDate } from '@/lib/format'
import { listContainer } from '@/lib/motion'
import type { TripResponse, TripType } from '@/api/types'

/** Tableau vide partage : evite de creer une nouvelle reference a chaque rendu. */
const NO_TRIPS: TripResponse[] = []

const SORT_KEYS: TripSearchSort[] = ['departure', 'price', 'rating']
const RATING_STEPS = [0, 3.5, 4, 4.5]
const TRIP_TYPES: TripType[] = ['INTERURBAIN', 'QUOTIDIEN']
const PLACE_KINDS: PlaceKind[] = ['CITY', 'DISTRICT', 'STATION']

/**
 * Tri et filtres vivent dans l'URL et sont appliques PAR LE SERVEUR (audit F137,
 * F219) : ils portent sur l'ensemble des departs, pas sur la page chargee, et
 * « Voir plus » reste toujours accessible. Un lien partage reproduit la meme vue.
 * Seul « places disponibles uniquement » (audit F334) se regle ici, sur les
 * cartes chargees : il masque les departs complets, actif par defaut.
 */
interface Filters {
  /** null = aucun plafond. */
  maxPrice: number | null
  minRating: number
  verifiedOnly: boolean
  availableOnly: boolean
}

function readSort(value: string | null): TripSearchSort {
  return SORT_KEYS.includes(value as TripSearchSort) ? (value as TripSearchSort) : 'departure'
}

/** `type` n'est envoye que s'il est valide (audit F246) : une valeur inconnue dans l'URL ne fabrique pas un 400. */
function readTripType(value: string | null): TripType | undefined {
  return TRIP_TYPES.includes(value as TripType) ? (value as TripType) : undefined
}

function readKind(value: string | null): PlaceKind | undefined {
  return PLACE_KINDS.includes(value as PlaceKind) ? (value as PlaceKind) : undefined
}

function readFilters(params: URLSearchParams): Filters {
  const maxPrice = Number(params.get('maxPrice'))
  const minRating = Number(params.get('minRating'))
  return {
    maxPrice: Number.isFinite(maxPrice) && maxPrice > 0 ? maxPrice : null,
    minRating: RATING_STEPS.includes(minRating) ? minRating : 0,
    verifiedOnly: params.get('verifiedOnly') === 'true',
    availableOnly: params.get('showFull') !== 'true',
  }
}

export function SearchResultsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()
  const authed = useIsAuthenticated()
  const online = useOnlineStatus()
  const desktop = useIsDesktop()
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [alertOpen, setAlertOpen] = useState(false)
  const createAlert = useCreateTripAlert()

  const sort = readSort(searchParams.get('sort'))
  const filters = useMemo(() => readFilters(searchParams), [searchParams])
  const tripType = readTripType(searchParams.get('type'))
  /** Mode de l'alerte : celui de la recherche, ou « les deux » si la recherche n'en avait pas. */
  const [alertType, setAlertType] = useState<TripType | 'ALL'>(tripType ?? 'ALL')

  /** Met a jour tri ou filtres dans l'URL sans toucher au reste de la recherche. */
  const updateParams = (patch: Record<string, string | null>) => {
    const next = new URLSearchParams(searchParams)
    for (const [key, value] of Object.entries(patch)) {
      if (value === null || value === '') next.delete(key)
      else next.set(key, value)
    }
    setSearchParams(next, { replace: true })
  }
  const setSort = (value: TripSearchSort) => updateParams({ sort: value === 'departure' ? null : value })
  const setFilters = (next: Filters) =>
    updateParams({
      maxPrice: next.maxPrice === null ? null : String(next.maxPrice),
      minRating: next.minRating > 0 ? String(next.minRating) : null,
      verifiedOnly: next.verifiedOnly ? 'true' : null,
      showFull: next.availableOnly ? null : 'true',
    })
  const resetFilters = () => setFilters({ maxPrice: null, minRating: 0, verifiedOnly: false, availableOnly: true })

  const dateParam = searchParams.get('date')
  const today = toInputDate(new Date())
  // Une date passee ne se cherche pas (audit F248) : un lien ancien ou une URL modifiee arrive ici.
  const dateInPast = dateParam !== null && /^\d{4}-\d{2}-\d{2}$/.test(dateParam) && dateParam < today

  const query = useMemo(() => {
    const fromLat = Number(searchParams.get('fromLat'))
    const fromLng = Number(searchParams.get('fromLng'))
    const toLat = Number(searchParams.get('toLat'))
    const toLng = Number(searchParams.get('toLng'))
    if ([fromLat, fromLng, toLat, toLng].some((n) => Number.isNaN(n) || n === 0)) return null
    if (dateInPast) return null
    const params: TripSearchParams = {
      originLat: fromLat,
      originLng: fromLng,
      destLat: toLat,
      destLng: toLng,
      originLabel: searchParams.get('from') ?? undefined,
      destLabel: searchParams.get('to') ?? undefined,
      date: dateParam ?? undefined,
      seats: Number(searchParams.get('seats')) || 1,
      tripType,
      // 4 km pour un quartier ou une gare, 5 km en urbain, 15 km en interurbain, jamais plus de la moitie de l'axe.
      radiusKm: searchRadiusKm(haversineKm(fromLat, fromLng, toLat, toLng), {
        origin: readKind(searchParams.get('fromKind')),
        destination: readKind(searchParams.get('toKind')),
      }),
      sort: sort === 'departure' ? undefined : sort,
      maxPrice: filters.maxPrice ?? undefined,
      minRating: filters.minRating > 0 ? filters.minRating : undefined,
      verifiedOnly: filters.verifiedOnly || undefined,
      size: 20,
    }
    return params
  }, [searchParams, sort, filters, tripType, dateParam, dateInPast])

  const fromLabel = searchParams.get('from') ?? 'Départ'
  const toLabel = searchParams.get('to') ?? 'Arrivée'
  const seats = Number(searchParams.get('seats')) || 1
  const clockDiffers = deviceClockDiffersFromBenin()

  const search = useTripSearchPages(query)
  const slow = useSlow(search.isFetching)
  const staleMinutes = useStaleAge(search.dataUpdatedAt || undefined)
  // Pages cumulees ; reference stable sans resultat.
  const loaded = useMemo(() => search.data?.pages.flatMap((page) => page.content) ?? NO_TRIPS, [search.data])
  const trips = useMemo(
    () => (filters.availableOnly ? loaded.filter((trip) => trip.seatsAvailable > 0) : loaded),
    [loaded, filters.availableOnly],
  )
  const hiddenFull = loaded.length - trips.length
  const totalResults = search.data?.pages[0]?.totalElements ?? loaded.length
  // Borne du curseur de prix : le plus cher des resultats charges, arrondi aux 500 F superieurs.
  const priceCeiling = useMemo(
    () => Math.max(5_000, Math.ceil(Math.max(0, ...loaded.map((t) => t.pricePerSeat)) / 500) * 500),
    [loaded],
  )

  const activeFilterCount =
    (filters.maxPrice !== null ? 1 : 0) + (filters.minRating > 0 ? 1 : 0) + (filters.verifiedOnly ? 1 : 0)

  const mapPoints = useMemo(() => {
    const fromLat = Number(searchParams.get('fromLat'))
    const fromLng = Number(searchParams.get('fromLng'))
    const toLat = Number(searchParams.get('toLat'))
    const toLng = Number(searchParams.get('toLng'))
    if ([fromLat, fromLng, toLat, toLng].some((n) => Number.isNaN(n) || n === 0)) return []
    return [
      { label: fromLabel, lat: fromLat, lng: fromLng, kind: 'origin' as const },
      { label: toLabel, lat: toLat, lng: toLng, kind: 'destination' as const },
    ]
  }, [searchParams, fromLabel, toLabel])

  const submitAlert = () => {
    if (!authed) {
      navigate(`/login?next=${encodeURIComponent(window.location.pathname + window.location.search)}`)
      return
    }
    const fromLat = Number(searchParams.get('fromLat'))
    const fromLng = Number(searchParams.get('fromLng'))
    const toLat = Number(searchParams.get('toLat'))
    const toLng = Number(searchParams.get('toLng'))
    createAlert.mutate(
      {
        originLabel: fromLabel,
        originLat: fromLat,
        originLng: fromLng,
        destLabel: toLabel,
        destLat: toLat,
        destLng: toLng,
        date: dateParam,
        seats,
        // null = tous les modes (contrat de l'API).
        tripType: alertType === 'ALL' ? null : alertType,
      },
      {
        onSuccess: () => {
          setAlertOpen(false)
          toast.success('Alerte créée', {
            description: `Vous serez prévenu dès qu'un trajet ${fromLabel} → ${toLabel} est publié.`,
            action: { label: 'Mes alertes', onClick: () => navigate('/me?tab=alerts') },
          })
        },
        onError: (error) =>
          toast.error(
            errorStatus(error) === 422
              ? 'Vous avez atteint le maximum de 10 alertes actives. Supprimez-en une depuis « Mes alertes ».'
              : describeError(error, "L'alerte n'a pas pu être créée."),
          ),
      },
    )
  }

  const pageTitle = `${fromLabel} → ${toLabel}${dateParam ? ` · ${formatDayShort(dateParam)}` : ''}`

  if (dateInPast) {
    return (
      <PageContainer width="md">
        <PageMeta title={pageTitle} noindex />
        <EmptyState
          icon={SearchX}
          headingLevel="h1"
          title="Cette date est passée"
          description={`Le ${formatDayShort(dateParam)} est derrière nous : choisissez aujourd'hui ou un jour à venir pour ${fromLabel} → ${toLabel}.`}
          action={
            <Button onClick={() => updateParams({ date: today })}>Chercher pour aujourd'hui</Button>
          }
        />
      </PageContainer>
    )
  }

  if (!query) {
    return (
      <PageContainer width="md">
        <PageMeta title="Recherche incomplète" noindex />
        <EmptyState
          icon={SearchX}
          headingLevel="h1"
          title="Recherche incomplète"
          description="Le départ et l'arrivée n'ont pas été transmis. Relancez la recherche depuis l'accueil."
          action={
            <Button asChild>
              <Link to="/">Retour à la recherche</Link>
            </Button>
          }
        />
      </PageContainer>
    )
  }

  const errorIsFinal = search.isError && isDefinitiveError(search.error)

  return (
    <PageContainer width="lg">
      <PageMeta
        title={pageTitle}
        description={`Covoiturages ${fromLabel} → ${toLabel}${dateParam ? ` le ${formatDayShort(dateParam)}` : ''} : acompte en mobile money, solde en espèces à bord.`}
      />
      {/* --- En-tete de recherche : rappel du critere, toujours visible --- */}
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:gap-x-3">
        <div className="min-w-0 sm:flex-1">
          <h1 tabIndex={-1} className="flex min-w-0 items-center gap-2 font-display text-heading font-extrabold tracking-[-0.03em] outline-none sm:text-display">
            <span className="truncate">{fromLabel}</span>
            <ArrowUpDown className="size-4 shrink-0 rotate-90 text-muted" aria-hidden />
            <span className="truncate">{toLabel}</span>
          </h1>
          <p className="mt-0.5 text-label text-muted">
            {dateParam ? formatDayShort(dateParam) : 'Toutes dates'} · {seats} place{seats > 1 ? 's' : ''}
            {search.isFetched ? ` · ${totalResults} départ${totalResults > 1 ? 's' : ''}` : ''}
            {clockDiffers ? ` · ${BENIN_TIME_HINT}` : ''}
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Select value={sort} onValueChange={(v) => setSort(readSort(v))}>
            <SelectTrigger
              className="h-11 min-w-0 flex-1 gap-2 text-body sm:w-auto sm:min-w-[148px] sm:flex-none"
              aria-label="Trier les résultats"
            >
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="departure">Départ le plus tôt</SelectItem>
              <SelectItem value="price">Prix croissant</SelectItem>
              <SelectItem value="rating">Meilleure note</SelectItem>
            </SelectContent>
          </Select>

          <Button variant="secondary" className="relative shrink-0" onClick={() => setFiltersOpen(true)}>
            <SlidersHorizontal className="size-4" aria-hidden />
            Filtres
            {activeFilterCount > 0 ? (
              <span className="tnum ml-1 flex size-5 items-center justify-center rounded-full bg-primary text-micro font-bold text-on-primary">
                {activeFilterCount}
              </span>
            ) : null}
          </Button>
        </div>
      </div>

      {/* --- Rappel « donnees enregistrees » quand on est hors ligne --- */}
      {!online && staleMinutes !== null ? (
        <Card className="mb-3 flex items-center gap-2.5 border-accent bg-accent-soft px-3 py-2.5 text-label font-medium text-accent-ink">
          <WifiOff className="size-4 shrink-0" aria-hidden />
          <span>
            Résultats enregistrés il y a {staleMinutes < 1 ? "moins d'une minute" : `${staleMinutes} min`}. Ils
            peuvent avoir changé.
          </span>
        </Card>
      ) : null}

      {activeFilterCount > 0 ? (
        <div className="mb-3 flex flex-wrap items-center gap-1.5">
          {filters.maxPrice !== null ? (
            <FilterChip
              label={`≤ ${formatFcfa(filters.maxPrice)}`}
              onClear={() => setFilters({ ...filters, maxPrice: null })}
            />
          ) : null}
          {filters.minRating > 0 ? (
            <FilterChip
              label={`Note ≥ ${filters.minRating.toFixed(1).replace('.', ',')}`}
              onClear={() => setFilters({ ...filters, minRating: 0 })}
            />
          ) : null}
          {filters.verifiedOnly ? (
            <FilterChip label="Vérifiés" onClear={() => setFilters({ ...filters, verifiedOnly: false })} />
          ) : null}
          <button
            type="button"
            onClick={resetFilters}
            className="ml-1 min-h-8 text-label font-medium text-primary-ink underline-offset-4 hover:underline"
          >
            Tout effacer
          </button>
        </div>
      ) : null}

      {/* --- Deux colonnes au-dela de 1024 px : liste + carte collante --- */}
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_360px]">
        <div>
          {slow ? <SlowNetworkNotice className="mb-3" /> : null}
          {isOfflineWithoutData(search) ? (
            <OfflineState
              description="Cette recherche n'a pas encore été enregistrée sur cet appareil. Elle se lancera dès que la connexion reviendra."
              onRetry={() => search.refetch()}
            />
          ) : search.isPending ? (
            <ListSkeleton count={5} />
          ) : search.isError ? (
            <ErrorState
              title={errorIsFinal ? 'Recherche impossible' : 'Chargement impossible'}
              description={describeError(search.error)}
              onRetry={errorIsFinal ? undefined : () => search.refetch()}
            />
          ) : trips.length === 0 ? (
            <EmptyState
              icon={SearchX}
              title={
                activeFilterCount > 0
                  ? 'Aucun trajet ne passe vos filtres'
                  : hiddenFull > 0
                    ? 'Tous les départs sont complets'
                    : 'Aucun trajet ce jour-là'
              }
              description={
                activeFilterCount > 0
                  ? 'Élargissez vos critères pour voir les autres départs disponibles.'
                  : hiddenFull > 0
                    ? `${hiddenFull} départ${hiddenFull > 1 ? 's' : ''} sans place restante ${hiddenFull > 1 ? 'sont masqués' : 'est masqué'}. Créez une alerte : nous vous prévenons dès qu'une place se libère.`
                    : `Personne ne part encore de ${fromLabel} vers ${toLabel} à cette date. Créez une alerte : nous vous prévenons dès qu'une place se libère.`
              }
              action={
                activeFilterCount > 0 ? (
                  <Button variant="secondary" onClick={resetFilters}>
                    Réinitialiser les filtres
                  </Button>
                ) : (
                  <Button onClick={() => setAlertOpen(true)}>
                    <BellPlus className="size-4" aria-hidden />
                    Créer une alerte
                  </Button>
                )
              }
            />
          ) : (
            <>
              <m.div
                // La cle force le rejeu de la cascade quand le tri ou les filtres changent.
                key={`${sort}-${activeFilterCount}`}
                variants={listContainer}
                initial="hidden"
                animate="show"
                className="space-y-3"
              >
                {trips.map((trip) => (
                  <TripCard key={trip.id} trip={trip} seats={seats} />
                ))}
              </m.div>

              {hiddenFull > 0 ? (
                <p className="mt-3 text-center text-label text-muted">
                  {hiddenFull} départ{hiddenFull > 1 ? 's' : ''} complet{hiddenFull > 1 ? 's' : ''} masqué{hiddenFull > 1 ? 's' : ''}.{' '}
                  <button
                    type="button"
                    onClick={() => setFilters({ ...filters, availableOnly: false })}
                    className="font-medium text-primary-ink underline-offset-4 hover:underline"
                  >
                    Les afficher
                  </button>
                </p>
              ) : null}

              {search.hasNextPage ? (
                <Button
                  variant="secondary"
                  block
                  className="mt-4"
                  loading={search.isFetchingNextPage}
                  onClick={() => search.fetchNextPage()}
                >
                  Voir plus de départs ({totalResults - loaded.length} restants)
                </Button>
              ) : null}

              <Card className="mt-4 flex flex-col items-start gap-3 p-4 sm:flex-row sm:items-center">
                <BellPlus className="size-5 shrink-0 text-primary-ink" aria-hidden />
                <p className="flex-1 text-body text-ink-2">
                  Aucun de ces départs ne convient ? Créez une alerte pour cet axe.
                </p>
                <Button variant="secondary" size="sm" onClick={() => setAlertOpen(true)}>
                  Créer une alerte
                </Button>
              </Card>
            </>
          )}
        </div>

        {/* La carte (MapLibre, 1 Mo) n'est montee que lorsqu'elle est visible : jamais sur mobile. */}
        {desktop ? (
          <aside>
            <div className="sticky top-24 space-y-3">
              <RouteMap points={mapPoints} className="h-[280px]" />
              <Card className="p-4">
                <h2 className="font-display text-body font-bold uppercase tracking-[0.06em] text-muted">
                  Repères de prix
                </h2>
                {loaded.length > 0 ? (
                  <dl className="mt-3 space-y-2 text-body">
                    <PriceRow label="Le moins cher" value={Math.min(...loaded.map((t) => t.pricePerSeat))} />
                    <PriceRow label="Prix médian" value={median(loaded.map((t) => t.pricePerSeat))} />
                    <PriceRow label="Le plus cher" value={Math.max(...loaded.map((t) => t.pricePerSeat))} />
                  </dl>
                ) : (
                  <p className="mt-2 text-label text-muted">Pas encore de repère pour cet axe.</p>
                )}
                {search.hasNextPage ? (
                  <p className="mt-2 text-caption text-muted">Sur les {loaded.length} premiers départs affichés.</p>
                ) : null}
              </Card>
            </div>
          </aside>
        ) : null}
      </div>

      {/* --- Feuille de filtres --- */}
      <Sheet
        open={filtersOpen}
        onOpenChange={setFiltersOpen}
        title="Filtrer les trajets"
        description={search.isFetched ? `${totalResults} départ${totalResults > 1 ? 's' : ''} correspondent` : undefined}
        footer={
          <div className="flex gap-2">
            <Button variant="ghost" block onClick={resetFilters}>
              Réinitialiser
            </Button>
            <Button block size="lg" onClick={() => setFiltersOpen(false)}>
              Voir les résultats
            </Button>
          </div>
        }
      >
        <div className="space-y-6 py-2">
          <div>
            <div className="mb-1 flex items-baseline justify-between">
              <span className="text-body font-semibold">Prix maximum</span>
              <span className="tnum font-display text-lead font-bold">
                {filters.maxPrice === null ? 'Sans limite' : formatFcfa(filters.maxPrice)}
              </span>
            </div>
            <Slider
              value={[filters.maxPrice ?? priceCeiling]}
              min={500}
              max={priceCeiling}
              step={500}
              onValueCommit={([value]) => setFilters({ ...filters, maxPrice: value >= priceCeiling ? null : value })}
              aria-label="Prix maximum par place"
            />
          </div>

          <fieldset>
            <legend className="mb-2 text-body font-semibold">Note minimale du conducteur</legend>
            <div className="flex gap-2">
              {RATING_STEPS.map((value) => (
                <button
                  key={value}
                  type="button"
                  aria-pressed={filters.minRating === value}
                  onClick={() => setFilters({ ...filters, minRating: value })}
                  className={
                    filters.minRating === value
                      ? 'flex min-h-11 flex-1 items-center justify-center gap-1 rounded-[var(--radius-control)] border border-primary bg-primary-soft text-label font-semibold text-primary-ink'
                      : 'flex min-h-11 flex-1 items-center justify-center gap-1 rounded-[var(--radius-control)] border border-field-border bg-surface text-label font-medium text-ink-2'
                  }
                >
                  {value === 0 ? (
                    'Toutes'
                  ) : (
                    <>
                      <Star className="size-3.5 fill-accent text-accent-ink" aria-hidden />
                      {value.toFixed(1).replace('.', ',')}
                    </>
                  )}
                </button>
              ))}
            </div>
            <p className="mt-1.5 text-caption text-muted">Un conducteur sans avis reste affiché.</p>
          </fieldset>

          <div className="divide-y divide-rule rounded-[var(--radius-card)] border border-rule">
            <label className="flex min-h-[56px] cursor-pointer items-center gap-3 px-4">
              <Checkbox
                checked={filters.verifiedOnly}
                onCheckedChange={(checked) => setFilters({ ...filters, verifiedOnly: checked === true })}
              />
              <span className="flex-1">
                <span className="block text-body font-medium">Conducteurs vérifiés uniquement</span>
                <span className="block text-caption text-muted">Identité contrôlée par l'équipe Ekuiseo</span>
              </span>
            </label>
            <label className="flex min-h-[56px] cursor-pointer items-center gap-3 px-4">
              <Checkbox
                checked={filters.availableOnly}
                onCheckedChange={(checked) => setFilters({ ...filters, availableOnly: checked === true })}
              />
              <span className="flex-1">
                <span className="block text-body font-medium">Places disponibles uniquement</span>
                <span className="block text-caption text-muted">Masque les départs déjà complets</span>
              </span>
            </label>
          </div>
        </div>
      </Sheet>

      {/* --- Feuille de creation d'alerte --- */}
      <Sheet
        open={alertOpen}
        onOpenChange={setAlertOpen}
        title="Créer une alerte"
        description="Nous vous prévenons dès qu'un trajet correspond."
        footer={
          <Button size="lg" block loading={createAlert.isPending} onClick={submitAlert}>
            Activer l'alerte
          </Button>
        }
      >
        <ul className="space-y-2 py-2 text-body">
          <li className="flex items-center gap-2.5 rounded-[var(--radius-control)] bg-surface-2 px-3 py-2.5">
            <CircleDot className="size-4 shrink-0 text-primary" aria-hidden />
            <span className="font-medium">{fromLabel}</span>
          </li>
          <li className="flex items-center gap-2.5 rounded-[var(--radius-control)] bg-surface-2 px-3 py-2.5">
            <Flag className="size-4 shrink-0 text-danger" aria-hidden />
            <span className="font-medium">{toLabel}</span>
          </li>
          <li className="flex items-center justify-between rounded-[var(--radius-control)] bg-surface-2 px-3 py-2.5">
            <span className="text-muted">Date</span>
            <span className="font-medium">{dateParam ? formatDayShort(dateParam) : 'Toutes dates'}</span>
          </li>
          <li className="flex items-center justify-between rounded-[var(--radius-control)] bg-surface-2 px-3 py-2.5">
            <span className="text-muted">Places</span>
            <span className="tnum font-medium">{seats}</span>
          </li>
        </ul>
        <SegmentedToggle
          label="Mode de trajet surveillé"
          value={alertType}
          onValueChange={setAlertType}
          className="mt-3"
          options={[
            { value: 'INTERURBAIN', label: 'Interurbain' },
            { value: 'QUOTIDIEN', label: 'Quotidien' },
            { value: 'ALL', label: 'Les deux' },
          ]}
        />
      </Sheet>
    </PageContainer>
  )
}

/* -------------------------------------------------------------- Utilitaires */

function median(values: number[]): number {
  const sorted = [...values].sort((a, b) => a - b)
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 === 0 ? Math.round((sorted[mid - 1] + sorted[mid]) / 2) : sorted[mid]
}

/** Filtre actif : la puce entiere est le bouton de retrait (cible 32 px, pas une croix de 20 px). */
function FilterChip({ label, onClear }: { label: string; onClear: () => void }) {
  return (
    <button
      type="button"
      onClick={onClear}
      aria-label={`Retirer le filtre ${label}`}
      className="group inline-flex h-8 items-center gap-1 rounded-[var(--radius-chip)] bg-primary-soft pl-2.5 pr-1.5 text-caption font-semibold text-primary-ink transition-colors hover:bg-primary hover:text-on-primary"
    >
      {label}
      <X className="size-3.5 opacity-70 group-hover:opacity-100" aria-hidden />
    </button>
  )
}

function PriceRow({ label, value }: { label: string; value: number }) {
  return (
    <div className="flex items-baseline justify-between">
      <dt className="text-muted">{label}</dt>
      <dd className="tnum font-display font-bold">{formatFcfa(value)}</dd>
    </div>
  )
}
