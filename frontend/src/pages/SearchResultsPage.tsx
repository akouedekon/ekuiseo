import { motion } from 'motion/react'
import {
  ArrowUpDown,
  BellPlus,
  CircleDot,
  Flag,
  SearchX,
  SlidersHorizontal,
  Star,
  WifiOff,
  X,
} from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/misc'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Sheet } from '@/components/ui/sheet'
import { Slider } from '@/components/ui/misc'
import { EmptyState, ErrorState, ListSkeleton } from '@/components/ui/states'
import { PageContainer } from '@/components/layout/PageContainer'
import { RouteMap } from '@/components/trip/RouteMap'
import { TripCard } from '@/components/trip/TripCard'
import { useCreateTripAlert } from '@/hooks/useAlerts'
import { useOnlineStatus, useStaleAge } from '@/hooks/useNetwork'
import { useTripSearch, type TripSearchParams } from '@/hooks/useTrips'
import { estimateDurationMinutes, haversineKm } from '@/lib/cities'
import { formatDayShort, formatFcfa } from '@/lib/format'
import { listContainer } from '@/lib/motion'
import type { TripResponse, TripType } from '@/api/types'

/** Tableau vide partage : evite de creer une nouvelle reference a chaque rendu. */
const NO_TRIPS: TripResponse[] = []

type SortKey = 'departure' | 'price' | 'rating' | 'duration'

interface Filters {
  maxPrice: number
  departureWindow: 'ALL' | 'MORNING' | 'AFTERNOON' | 'EVENING'
  minRating: number
  verifiedOnly: boolean
  instantOnly: boolean
}

const DEFAULT_FILTERS: Filters = {
  maxPrice: 20_000,
  departureWindow: 'ALL',
  minRating: 0,
  verifiedOnly: false,
  instantOnly: false,
}

const WINDOW_LABEL: Record<Filters['departureWindow'], string> = {
  ALL: 'Toute la journée',
  MORNING: 'Matin (avant 12 h)',
  AFTERNOON: 'Après-midi (12 h – 17 h)',
  EVENING: 'Soir (après 17 h)',
}

export function SearchResultsPage() {
  const [searchParams] = useSearchParams()
  const online = useOnlineStatus()
  const [sort, setSort] = useState<SortKey>('departure')
  const [filters, setFilters] = useState<Filters>(DEFAULT_FILTERS)
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [alertOpen, setAlertOpen] = useState(false)
  const createAlert = useCreateTripAlert()

  const query = useMemo(() => {
    const fromLat = Number(searchParams.get('fromLat'))
    const fromLng = Number(searchParams.get('fromLng'))
    const toLat = Number(searchParams.get('toLat'))
    const toLng = Number(searchParams.get('toLng'))
    if ([fromLat, fromLng, toLat, toLng].some((n) => Number.isNaN(n) || n === 0)) return null
    const params: TripSearchParams = {
      originLat: fromLat,
      originLng: fromLng,
      destLat: toLat,
      destLng: toLng,
      originLabel: searchParams.get('from') ?? undefined,
      destLabel: searchParams.get('to') ?? undefined,
      date: searchParams.get('date') ?? undefined,
      seats: Number(searchParams.get('seats')) || 1,
      tripType: (searchParams.get('type') as TripType | null) ?? undefined,
      radiusKm: 15,
      size: 30,
    }
    return params
  }, [searchParams])

  const fromLabel = searchParams.get('from') ?? 'Départ'
  const toLabel = searchParams.get('to') ?? 'Arrivée'
  const dateParam = searchParams.get('date')
  const seats = Number(searchParams.get('seats')) || 1

  const search = useTripSearch(query)
  const staleMinutes = useStaleAge(search.dataUpdatedAt || undefined)
  // Reference stable quand il n'y a pas encore de resultat : sinon le tri et
  // le filtrage seraient recalcules a chaque rendu.
  const trips = search.data?.data.content ?? NO_TRIPS

  const activeFilterCount =
    (filters.maxPrice < DEFAULT_FILTERS.maxPrice ? 1 : 0) +
    (filters.departureWindow !== 'ALL' ? 1 : 0) +
    (filters.minRating > 0 ? 1 : 0) +
    (filters.verifiedOnly ? 1 : 0) +
    (filters.instantOnly ? 1 : 0)

  const visible = useMemo(() => filterAndSort(trips, filters, sort), [trips, filters, sort])

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
        tripType: (searchParams.get('type') as TripType | null) ?? 'INTERURBAIN',
      },
      {
        onSuccess: () => {
          setAlertOpen(false)
          toast.success('Alerte créée', {
            description: `Vous serez prévenu dès qu'un trajet ${fromLabel} → ${toLabel} est publié.`,
          })
        },
        onError: () => toast.error("L'alerte n'a pas pu être créée."),
      },
    )
  }

  if (!query) {
    return (
      <PageContainer width="md">
        <EmptyState
          icon={SearchX}
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

  return (
    <PageContainer width="lg">

      {/* --- En-tete de recherche : rappel du critere, toujours visible --- */}
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:gap-x-3">
        <div className="min-w-0 sm:flex-1">
          <h1 className="flex min-w-0 items-center gap-2 font-display text-[22px] font-extrabold tracking-[-0.03em] sm:text-[26px]">
            <span className="truncate">{fromLabel}</span>
            <ArrowUpDown className="size-4 shrink-0 rotate-90 text-muted" aria-hidden />
            <span className="truncate">{toLabel}</span>
          </h1>
          <p className="mt-0.5 text-[13px] text-muted">
            {dateParam ? formatDayShort(dateParam) : 'Toutes dates'} · {seats} place{seats > 1 ? 's' : ''}
            {search.isFetched ? ` · ${visible.length} résultat${visible.length > 1 ? 's' : ''}` : ''}
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Select value={sort} onValueChange={(v) => setSort(v as SortKey)}>
            <SelectTrigger className="h-11 min-w-0 flex-1 gap-2 text-[14px] sm:w-auto sm:min-w-[148px] sm:flex-none" aria-label="Trier les résultats">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="departure">Départ le plus tôt</SelectItem>
              <SelectItem value="price">Prix croissant</SelectItem>
              <SelectItem value="rating">Meilleure note</SelectItem>
              <SelectItem value="duration">Trajet le plus court</SelectItem>
            </SelectContent>
          </Select>

          <Button variant="secondary" className="relative shrink-0" onClick={() => setFiltersOpen(true)}>
            <SlidersHorizontal className="size-4" aria-hidden />
            Filtres
            {activeFilterCount > 0 ? (
              <span className="tnum ml-1 flex size-5 items-center justify-center rounded-full bg-[var(--indigo)] text-[11px] font-bold text-[var(--indigo-contrast)]">
                {activeFilterCount}
              </span>
            ) : null}
          </Button>
        </div>
      </div>

      {/* --- Rappel « donnees enregistrees » quand on est hors ligne --- */}
      {!online && staleMinutes !== null ? (
        <Card className="mb-3 flex items-center gap-2.5 border-[var(--ocre)] bg-[var(--ocre-soft)] px-3 py-2.5 text-[13px] font-medium text-[var(--ocre-ink)]">
          <WifiOff className="size-4 shrink-0" aria-hidden />
          <span>
            Résultats enregistrés il y a {staleMinutes < 1 ? "moins d'une minute" : `${staleMinutes} min`}. Ils
            peuvent avoir changé.
          </span>
        </Card>
      ) : null}

      {activeFilterCount > 0 ? (
        <div className="mb-3 flex flex-wrap items-center gap-1.5">
          {filters.maxPrice < DEFAULT_FILTERS.maxPrice ? (
            <FilterChip
              label={`≤ ${formatFcfa(filters.maxPrice)}`}
              onClear={() => setFilters((f) => ({ ...f, maxPrice: DEFAULT_FILTERS.maxPrice }))}
            />
          ) : null}
          {filters.departureWindow !== 'ALL' ? (
            <FilterChip
              label={WINDOW_LABEL[filters.departureWindow]}
              onClear={() => setFilters((f) => ({ ...f, departureWindow: 'ALL' }))}
            />
          ) : null}
          {filters.minRating > 0 ? (
            <FilterChip
              label={`Note ≥ ${filters.minRating.toFixed(1).replace('.', ',')}`}
              onClear={() => setFilters((f) => ({ ...f, minRating: 0 }))}
            />
          ) : null}
          {filters.verifiedOnly ? (
            <FilterChip label="Vérifiés" onClear={() => setFilters((f) => ({ ...f, verifiedOnly: false }))} />
          ) : null}
          {filters.instantOnly ? (
            <FilterChip
              label="Réservation immédiate"
              onClear={() => setFilters((f) => ({ ...f, instantOnly: false }))}
            />
          ) : null}
          <button
            type="button"
            onClick={() => setFilters(DEFAULT_FILTERS)}
            className="ml-1 text-[13px] font-medium text-[var(--indigo)] underline-offset-4 hover:underline"
          >
            Tout effacer
          </button>
        </div>
      ) : null}

      {/* --- Deux colonnes au-dela de 1024 px : liste + carte collante --- */}
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_360px]">
        <div>
          {search.isPending ? (
            <ListSkeleton count={5} />
          ) : search.isError ? (
            <ErrorState onRetry={() => search.refetch()} />
          ) : visible.length === 0 ? (
            <EmptyState
              icon={SearchX}
              title={trips.length === 0 ? 'Aucun trajet ce jour-là' : 'Aucun trajet ne passe vos filtres'}
              description={
                trips.length === 0
                  ? `Personne ne part encore de ${fromLabel} vers ${toLabel} à cette date. Créez une alerte : nous vous prévenons dès qu'une place se libère.`
                  : 'Élargissez vos critères pour voir les autres départs disponibles.'
              }
              action={
                trips.length === 0 ? (
                  <Button onClick={() => setAlertOpen(true)}>
                    <BellPlus className="size-4" aria-hidden />
                    Créer une alerte
                  </Button>
                ) : (
                  <Button variant="secondary" onClick={() => setFilters(DEFAULT_FILTERS)}>
                    Réinitialiser les filtres
                  </Button>
                )
              }
            />
          ) : (
            <>
              <motion.div
                // La cle force le rejeu de la cascade quand le tri ou les filtres changent.
                key={`${sort}-${activeFilterCount}`}
                variants={listContainer}
                initial="hidden"
                animate="show"
                className="space-y-3"
              >
                {visible.map((trip) => (
                  <TripCard key={trip.id} trip={trip} />
                ))}
              </motion.div>

              <Card className="mt-4 flex flex-col items-start gap-3 p-4 sm:flex-row sm:items-center">
                <BellPlus className="size-5 shrink-0 text-[var(--indigo)]" aria-hidden />
                <p className="flex-1 text-[14px] text-ink-2">
                  Aucun de ces départs ne convient ? Créez une alerte pour cet axe.
                </p>
                <Button variant="secondary" size="sm" onClick={() => setAlertOpen(true)}>
                  Créer une alerte
                </Button>
              </Card>
            </>
          )}
        </div>

        <aside className="hidden lg:block">
          <div className="sticky top-24 space-y-3">
            <RouteMap points={mapPoints} className="h-[280px]" />
            <Card className="p-4">
              <h2 className="font-display text-[14px] font-bold uppercase tracking-[0.06em] text-muted">
                Repères de prix
              </h2>
              {visible.length > 0 ? (
                <dl className="mt-3 space-y-2 text-[14px]">
                  <PriceRow label="Le moins cher" value={Math.min(...visible.map((t) => t.pricePerSeat))} />
                  <PriceRow
                    label="Prix médian"
                    value={median(visible.map((t) => t.pricePerSeat))}
                  />
                  <PriceRow label="Le plus cher" value={Math.max(...visible.map((t) => t.pricePerSeat))} />
                </dl>
              ) : (
                <p className="mt-2 text-[13px] text-muted">Pas encore de repère pour cet axe.</p>
              )}
            </Card>
          </div>
        </aside>
      </div>

      {/* --- Feuille de filtres --- */}
      <Sheet
        open={filtersOpen}
        onOpenChange={setFiltersOpen}
        title="Filtrer les trajets"
        description={`${visible.length} trajet${visible.length > 1 ? 's' : ''} correspondent`}
        footer={
          <div className="flex gap-2">
            <Button variant="ghost" block onClick={() => setFilters(DEFAULT_FILTERS)}>
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
              <span className="text-[14px] font-semibold">Prix maximum</span>
              <span className="tnum font-display text-[16px] font-bold">{formatFcfa(filters.maxPrice)}</span>
            </div>
            <Slider
              value={[filters.maxPrice]}
              min={500}
              max={20_000}
              step={500}
              onValueChange={([value]) => setFilters((f) => ({ ...f, maxPrice: value }))}
              aria-label="Prix maximum par place"
            />
          </div>

          <fieldset>
            <legend className="mb-2 text-[14px] font-semibold">Heure de départ</legend>
            <div className="grid grid-cols-2 gap-2">
              {(Object.keys(WINDOW_LABEL) as Filters['departureWindow'][]).map((key) => (
                <button
                  key={key}
                  type="button"
                  aria-pressed={filters.departureWindow === key}
                  onClick={() => setFilters((f) => ({ ...f, departureWindow: key }))}
                  className={
                    filters.departureWindow === key
                      ? 'min-h-11 rounded-[var(--radius-control)] border border-[var(--indigo)] bg-[var(--indigo-soft)] px-3 text-[13px] font-semibold text-[var(--indigo-deep)]'
                      : 'min-h-11 rounded-[var(--radius-control)] border border-rule-strong bg-surface px-3 text-[13px] font-medium text-ink-2'
                  }
                >
                  {WINDOW_LABEL[key]}
                </button>
              ))}
            </div>
          </fieldset>

          <fieldset>
            <legend className="mb-2 text-[14px] font-semibold">Note minimale du conducteur</legend>
            <div className="flex gap-2">
              {[0, 3.5, 4, 4.5].map((value) => (
                <button
                  key={value}
                  type="button"
                  aria-pressed={filters.minRating === value}
                  onClick={() => setFilters((f) => ({ ...f, minRating: value }))}
                  className={
                    filters.minRating === value
                      ? 'flex min-h-11 flex-1 items-center justify-center gap-1 rounded-[var(--radius-control)] border border-[var(--indigo)] bg-[var(--indigo-soft)] text-[13px] font-semibold text-[var(--indigo-deep)]'
                      : 'flex min-h-11 flex-1 items-center justify-center gap-1 rounded-[var(--radius-control)] border border-rule-strong bg-surface text-[13px] font-medium text-ink-2'
                  }
                >
                  {value === 0 ? (
                    'Toutes'
                  ) : (
                    <>
                      <Star className="size-3.5 fill-[var(--ocre)] text-[var(--ocre)]" aria-hidden />
                      {value.toFixed(1).replace('.', ',')}
                    </>
                  )}
                </button>
              ))}
            </div>
          </fieldset>

          <div className="divide-y divide-rule rounded-[var(--radius-card)] border border-rule">
            <label className="flex min-h-[56px] cursor-pointer items-center gap-3 px-4">
              <Checkbox
                checked={filters.verifiedOnly}
                onCheckedChange={(checked) => setFilters((f) => ({ ...f, verifiedOnly: checked === true }))}
              />
              <span className="flex-1">
                <span className="block text-[14px] font-medium">Conducteurs vérifiés uniquement</span>
                <span className="block text-[12px] text-muted">Pièce d'identité contrôlée par Ekuiseo</span>
              </span>
            </label>
            <label className="flex min-h-[56px] cursor-pointer items-center gap-3 px-4">
              <Checkbox
                checked={filters.instantOnly}
                onCheckedChange={(checked) => setFilters((f) => ({ ...f, instantOnly: checked === true }))}
              />
              <span className="flex-1">
                <span className="block text-[14px] font-medium">Réservation immédiate</span>
                <span className="block text-[12px] text-muted">Sans attendre l'accord du conducteur</span>
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
        <ul className="space-y-2 py-2 text-[14px]">
          <li className="flex items-center gap-2.5 rounded-[var(--radius-control)] bg-[var(--surface-calm)] px-3 py-2.5">
            <CircleDot className="size-4 shrink-0 text-[var(--indigo)]" aria-hidden />
            <span className="font-medium">{fromLabel}</span>
          </li>
          <li className="flex items-center gap-2.5 rounded-[var(--radius-control)] bg-[var(--surface-calm)] px-3 py-2.5">
            <Flag className="size-4 shrink-0 text-[var(--vermillon)]" aria-hidden />
            <span className="font-medium">{toLabel}</span>
          </li>
          <li className="flex items-center justify-between rounded-[var(--radius-control)] bg-[var(--surface-calm)] px-3 py-2.5">
            <span className="text-muted">Date</span>
            <span className="font-medium">{dateParam ? formatDayShort(dateParam) : 'Toutes dates'}</span>
          </li>
          <li className="flex items-center justify-between rounded-[var(--radius-control)] bg-[var(--surface-calm)] px-3 py-2.5">
            <span className="text-muted">Places</span>
            <span className="tnum font-medium">{seats}</span>
          </li>
        </ul>
      </Sheet>
    </PageContainer>
  )
}

/* -------------------------------------------------------------- Utilitaires */

function filterAndSort(trips: TripResponse[], filters: Filters, sort: SortKey): TripResponse[] {
  const filtered = trips.filter((trip) => {
    if (trip.pricePerSeat > filters.maxPrice) return false
    if (trip.driver.ratingAvg < filters.minRating) return false
    if (filters.instantOnly && !trip.instantBooking) return false
    // Drapeau serveur (DriverSummary.identityVerified) ; si un ancien backend ne
    // l'envoie pas encore, on retombe sur l'heuristique note + volume d'avis.
    if (filters.verifiedOnly && !(trip.driver.identityVerified ?? trip.driver.ratingCount >= 5)) return false
    if (filters.departureWindow !== 'ALL') {
      const hour = new Date(trip.departureAt).getHours()
      if (filters.departureWindow === 'MORNING' && hour >= 12) return false
      if (filters.departureWindow === 'AFTERNOON' && (hour < 12 || hour >= 17)) return false
      if (filters.departureWindow === 'EVENING' && hour < 17) return false
    }
    return true
  })

  const duration = (trip: TripResponse) =>
    estimateDurationMinutes(haversineKm(trip.originLat, trip.originLng, trip.destLat, trip.destLng))

  return filtered.sort((a, b) => {
    switch (sort) {
      case 'price':
        return a.pricePerSeat - b.pricePerSeat
      case 'rating':
        return b.driver.ratingAvg - a.driver.ratingAvg
      case 'duration':
        return duration(a) - duration(b)
      default:
        return new Date(a.departureAt).getTime() - new Date(b.departureAt).getTime()
    }
  })
}

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
      className="group inline-flex h-8 items-center gap-1 rounded-[var(--radius-chip)] bg-[var(--indigo-soft)] pl-2.5 pr-1.5 text-caption font-semibold text-[var(--indigo-deep)] transition-colors hover:bg-[var(--indigo)] hover:text-[var(--indigo-contrast)]"
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
