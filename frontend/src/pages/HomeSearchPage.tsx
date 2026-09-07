import { m } from 'motion/react'
import {
  ArrowRight,
  ArrowUpDown,
  CalendarDays,
  CircleDot,
  Clock,
  Flag,
  Search,
  ShieldCheck,
  Sparkles,
  TrendingUp,
  Wallet,
  WifiOff,
} from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { SegmentedToggle } from '@/components/ui/tabs'
import { Skeleton, Stepper } from '@/components/ui/misc'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { CityAutocomplete } from '@/components/trip/CityAutocomplete'
import { PageContainer, SectionTitle } from '@/components/layout/PageContainer'
import { PageMeta } from '@/components/layout/PageMeta'
import { useIsAuthenticated } from '@/hooks/useAuth'
import { usePopularRoutes, useRecurringTrips } from '@/hooks/useTrips'
import type { CityOption } from '@/lib/cities'
import { BENIN_TIME_HINT, deviceClockDiffersFromBenin, formatFcfa, toInputDate } from '@/lib/format'
import { WEEKDAYS } from '@/lib/labels'
import { DEPOSIT_FLOOR } from '@/lib/payments'
import { listContainer, listItem } from '@/lib/motion'
import { rememberPlace } from '@/lib/recentPlaces'
import type { PopularRouteResponse, RecurringTripResponse } from '@/api/extended'
import type { TripType } from '@/api/types'

/** Promesses produit : trois, pas plus, chacune avec sa teinte de signal. Rien que le produit ne tienne (audit F323). */
const PROMISES = [
  {
    icon: Wallet,
    tone: 'bg-success-soft text-success-ink',
    title: 'Paiement en deux temps',
    text: `Un acompte en mobile money, à partir de ${formatFcfa(DEPOSIT_FLOOR)}, bloque la place. Le solde se règle en espèces au conducteur, à bord.`,
  },
  {
    icon: ShieldCheck,
    tone: 'bg-primary-soft text-primary-ink',
    title: 'Conducteurs identifiés',
    text: "Identité déclarée et contrôlée par l'équipe Ekuiseo, e-mail confirmé, avis publics après chaque trajet.",
  },
  {
    icon: WifiOff,
    tone: 'bg-accent-soft text-accent-ink',
    title: "Pensé pour le réseau d'ici",
    text: 'Vos derniers résultats et trajets consultés restent lisibles hors ligne, le temps que la connexion revienne.',
  },
]

/** Jour civil au Benin (pas celui de l'appareil) : c'est lui que le serveur compare. */
function todayIso(): string {
  return toInputDate(new Date())
}

function readTripType(value: string | null): TripType {
  return value === 'QUOTIDIEN' ? 'QUOTIDIEN' : 'INTERURBAIN'
}

export function HomeSearchPage() {
  const navigate = useNavigate()
  const authed = useIsAuthenticated()
  const [searchParams] = useSearchParams()

  // Le mode vient de l'URL quand il est fourni (retour de connexion « ?next=/?type=QUOTIDIEN », audit F253).
  const [tripType, setTripType] = useState<TripType>(() => readTripType(searchParams.get('type')))
  const [origin, setOrigin] = useState<CityOption | null>(null)
  const [destination, setDestination] = useState<CityOption | null>(null)
  const [date, setDate] = useState(todayIso())
  const [seats, setSeats] = useState(1)
  const [touched, setTouched] = useState(false)
  const [dateError, setDateError] = useState<string>()

  const recurring = useRecurringTrips(authed && tripType === 'QUOTIDIEN')
  // Axes reellement proposes en ce moment : la liste vient du serveur, jamais d'une constante.
  const popular = usePopularRoutes(4)
  const popularRoutes = popular.data ?? []
  const clockDiffers = deviceClockDiffersFromBenin()

  const errors = useMemo(
    () => ({
      origin: touched && !origin ? 'Indiquez le départ' : undefined,
      destination: touched && !destination ? "Indiquez l'arrivée" : undefined,
    }),
    [touched, origin, destination],
  )

  const swap = () => {
    setOrigin(destination)
    setDestination(origin)
  }

  const goTo = (from: CityOption, to: CityOption) => {
    rememberPlace(from)
    rememberPlace(to)
    const params = new URLSearchParams({
      from: from.label,
      fromLat: String(from.lat),
      fromLng: String(from.lng),
      to: to.label,
      toLat: String(to.lat),
      toLng: String(to.lng),
      date,
      seats: String(seats),
      type: tripType,
    })
    // La nature des lieux (quartier, gare) serre le rayon de recherche (audit F422).
    if (from.kind) params.set('fromKind', from.kind)
    if (to.kind) params.set('toKind', to.kind)
    navigate(`/search?${params.toString()}`)
  }

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    setTouched(true)
    // Une date passee n'est pas une recherche (audit F248) : on le dit ici, pas dans une liste vide.
    if (date && date < todayIso()) {
      setDateError('Cette date est passée : choisissez aujourd’hui ou un jour à venir.')
      return
    }
    setDateError(undefined)
    if (!origin || !destination) return
    goTo(origin, destination)
  }

  /** Une navette memorisee porte ses coordonnees serveur : on ne devine jamais la ville. */
  const goToRoute = (item: RecurringTripResponse) => {
    const from: CityOption = { label: item.originLabel, lat: item.originLat, lng: item.originLng, region: '' }
    const to: CityOption = { label: item.destLabel, lat: item.destLat, lng: item.destLng, region: '' }
    setOrigin(from)
    setDestination(to)
    goTo(from, to)
  }

  /** Un axe du serveur porte ses propres coordonnees : pas besoin de la liste locale. */
  const goToPopular = (route: PopularRouteResponse) => {
    const from: CityOption = { label: route.originLabel, lat: route.originLat, lng: route.originLng, region: '' }
    const to: CityOption = { label: route.destLabel, lat: route.destLat, lng: route.destLng, region: '' }
    setOrigin(from)
    setDestination(to)
    goTo(from, to)
  }

  return (
    <div className="relative">
      <PageMeta />
      {/* Nappe lumineuse et grille pointillee : la seule matiere decorative de l'application. */}
      <div aria-hidden className="ek-glow pointer-events-none absolute inset-x-0 top-0 h-[520px]" />
      <div aria-hidden className="ek-dots pointer-events-none absolute inset-x-0 top-0 h-[420px]" />

      <PageContainer width="lg" className="relative pb-12 sm:pt-12">
        {/* --- Hero --- */}
        <m.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4 }}
          className="mx-auto max-w-2xl text-center"
        >
          <Badge tone="indigo" className="mb-4 gap-1.5 px-2.5 py-1">
            <Sparkles aria-hidden />
            Covoiturage au Bénin · interurbain et quotidien
          </Badge>
          <h1 tabIndex={-1} className="headline text-display-lg outline-none sm:text-hero">
            Partagez la route,
            <br />
            partagez le prix.
          </h1>
          <p className="mx-auto mt-4 max-w-xl text-base leading-relaxed text-ink-2 sm:text-lead">
            Cotonou, Bohicon, Parakou, Porto-Novo, Lomé… Un acompte en mobile money bloque votre place, le reste se
            règle en espèces à bord — ou tout en ligne, à votre choix.
          </p>
        </m.div>

        {/* --- Panneau de recherche --- */}
        <m.div
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.45, delay: 0.08 }}
          className="mx-auto mt-8 max-w-3xl"
        >
          <Card className="overflow-visible p-4 shadow-e3 sm:p-6">
            <form onSubmit={submit} noValidate>
              <SegmentedToggle
                label="Type de trajet"
                value={tripType}
                onValueChange={setTripType}
                className="mb-5"
                options={[
                  { value: 'INTERURBAIN', label: 'Interurbain', hint: 'Ville à ville' },
                  { value: 'QUOTIDIEN', label: 'Quotidien', hint: 'Navette régulière' },
                ]}
              />

              <div className="relative grid gap-3 sm:grid-cols-2">
                <CityAutocomplete
                  label="Départ"
                  value={origin}
                  onChange={setOrigin}
                  exclude={destination}
                  error={errors.origin}
                  icon={<CircleDot />}
                  placeholder="D'où partez-vous ?"
                />
                <CityAutocomplete
                  label="Arrivée"
                  value={destination}
                  onChange={setDestination}
                  exclude={origin}
                  error={errors.destination}
                  icon={<Flag />}
                  placeholder="Où allez-vous ?"
                />
                {/*
                 * Inversion (audit F328) : cible de 44 px, visible sur mobile a droite entre
                 * les deux champs, centree sur la couture des deux colonnes au-dela de 640 px.
                 */}
                <button
                  type="button"
                  onClick={swap}
                  aria-label="Inverser le départ et l'arrivée"
                  className="absolute right-0 top-[64px] z-10 flex size-11 -translate-y-1/2 items-center justify-center rounded-full border border-field-border bg-surface text-ink-2 shadow-e1 transition-[transform,color] hover:rotate-180 hover:text-primary-ink sm:left-1/2 sm:right-auto sm:top-[40px] sm:size-10 sm:-translate-x-1/2 sm:translate-y-0"
                >
                  <ArrowUpDown className="size-4" aria-hidden />
                </button>
              </div>

              <div className="mt-3 grid gap-3 sm:grid-cols-[minmax(0,1fr)_auto]">
                <Input
                  type="date"
                  label={tripType === 'QUOTIDIEN' ? 'À partir du' : 'Date de départ'}
                  value={date}
                  min={todayIso()}
                  onChange={(event) => {
                    setDate(event.target.value)
                    setDateError(undefined)
                  }}
                  error={dateError}
                  hint={clockDiffers ? `Jour compté en ${BENIN_TIME_HINT}.` : undefined}
                  leading={<CalendarDays />}
                  className="h-12"
                />
                <div className="flex flex-col gap-1.5">
                  <span className="text-label font-medium text-ink-2">Places</span>
                  <div className="flex h-12 items-center">
                    <Stepper
                      value={seats}
                      onChange={setSeats}
                      min={1}
                      max={8}
                      label="places"
                      decrementLabel="Une place de moins"
                      incrementLabel="Une place de plus"
                    />
                  </div>
                </div>
              </div>

              <Button type="submit" size="lg" block className="mt-5">
                <Search aria-hidden />
                Rechercher un trajet
              </Button>
            </form>
          </Card>
        </m.div>

        {/* --- Trajet de la semaine (mode quotidien) --- */}
        {tripType === 'QUOTIDIEN' ? (
          <section aria-labelledby="recurring-title" className="mx-auto mt-10 max-w-3xl">
            <SectionTitle>
              <span id="recurring-title">Votre trajet de la semaine</span>
            </SectionTitle>
            {!authed ? (
              <Card className="flex flex-col items-start gap-3 p-5 sm:flex-row sm:items-center">
                <span className="flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-primary-soft text-primary-ink">
                  <Sparkles className="size-5" aria-hidden />
                </span>
                <p className="flex-1 text-body text-ink-2">
                  Connectez-vous pour retrouver vos navettes habituelles et les départs correspondants en un geste.
                </p>
                <Button asChild variant="secondary" size="sm">
                  {/* Retour sur l'accueil EN MODE QUOTIDIEN apres connexion (audit F253). */}
                  <Link to="/login?next=%2F%3Ftype%3DQUOTIDIEN">Se connecter</Link>
                </Button>
              </Card>
            ) : recurring.isPending ? (
              <Card className="p-5">
                <Skeleton className="h-4 w-40" />
                <Skeleton className="mt-3 h-8 w-full" />
              </Card>
            ) : recurring.isError ? (
              <ErrorState
                title="Navettes indisponibles"
                description="Impossible de charger vos navettes habituelles pour l'instant."
                onRetry={() => recurring.refetch()}
              />
            ) : recurring.data && recurring.data.length > 0 ? (
              <m.div variants={listContainer} initial="hidden" animate="show" className="space-y-3">
                {recurring.data.map((item) => (
                  <m.div key={item.id} variants={listItem}>
                    <Card className="p-5">
                      <div className="flex flex-wrap items-start justify-between gap-3">
                        <div className="min-w-0">
                          <p className="font-display text-lead font-bold leading-tight">
                            {item.originLabel} → {item.destLabel}
                          </p>
                          <p className="tnum mt-1 flex items-center gap-1.5 text-label text-muted">
                            <Clock className="size-3.5" aria-hidden />
                            {item.departureTime} · {item.seats} place{item.seats > 1 ? 's' : ''}
                          </p>
                        </div>
                        <Badge tone={item.matchesAvailable > 0 ? 'success' : 'neutral'}>
                          {item.matchesAvailable > 0 ? `${item.matchesAvailable} départs disponibles` : 'Aucun départ'}
                        </Badge>
                      </div>

                      <ul className="mt-3 flex gap-1" aria-label="Jours de circulation">
                        {WEEKDAYS.map((day) => {
                          const active = item.weekdays.includes(day.value)
                          return (
                            <li
                              key={day.value}
                              className={
                                active
                                  ? 'flex size-7 items-center justify-center rounded-[var(--radius-chip)] bg-primary text-caption font-bold text-on-primary'
                                  : 'flex size-7 items-center justify-center rounded-[var(--radius-chip)] bg-surface-2 text-caption font-semibold text-muted'
                              }
                            >
                              <span aria-hidden>{day.letter}</span>
                              <span className="sr-only">
                                {day.name} : {active ? 'oui' : 'non'}
                              </span>
                            </li>
                          )
                        })}
                      </ul>

                      <Button
                        variant="outlineBrand"
                        size="sm"
                        block
                        className="mt-4"
                        onClick={() => goToRoute(item)}
                      >
                        Voir les départs
                      </Button>
                    </Card>
                  </m.div>
                ))}
              </m.div>
            ) : (
              <Card>
                {/* Formulation honnete (audit F250) : rien n'est « memorise » a la demande, la navette apparait avec l'usage. */}
                <EmptyState
                  icon={Clock}
                  title="Aucune navette pour l'instant"
                  description="Vos navettes apparaissent ici après deux réservations sur le même trajet quotidien."
                  action={
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() => document.getElementById('contenu')?.querySelector<HTMLInputElement>('input[role=combobox]')?.focus()}
                    >
                      Lancer une recherche quotidienne
                    </Button>
                  }
                  className="py-8"
                />
              </Card>
            )}
          </section>
        ) : null}

        {/* --- Axes proposes en ce moment (donnees serveur) ; rien n'est affiche s'il n'y en a aucun (audit L9) --- */}
        {popular.isPending || popular.isError || popularRoutes.length > 0 ? (
          <section aria-labelledby="popular-title" className="mt-12">
            <SectionTitle>
              <span id="popular-title">Départs proposés en ce moment</span>
            </SectionTitle>
            {popular.isError ? (
              <ErrorState
                title="Départs indisponibles"
                description="Impossible de charger les axes proposés pour l'instant."
                onRetry={() => popular.refetch()}
              />
            ) : popular.isPending ? (
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                {[0, 1, 2, 3].map((i) => (
                  <Skeleton key={i} className="h-[72px] rounded-[var(--radius-card)]" />
                ))}
              </div>
            ) : (
              <m.ul
                variants={listContainer}
                initial="hidden"
                animate="show"
                className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4"
              >
                {popularRoutes.map((route) => (
                  <m.li key={`${route.originLabel}-${route.destLabel}`} variants={listItem}>
                    <button
                      type="button"
                      onClick={() => goToPopular(route)}
                      className="ek-lift group flex w-full items-center gap-3 rounded-[var(--radius-card)] border border-rule bg-surface p-4 text-left shadow-e1"
                    >
                      <span className="flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-surface-2 text-ink-2 transition-colors group-hover:bg-primary-soft group-hover:text-primary-ink">
                        <TrendingUp className="size-[18px]" aria-hidden />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate font-display text-body font-bold text-ink">
                          {route.originLabel} → {route.destLabel}
                        </span>
                        <span className="tnum block text-label text-muted">
                          {route.trips} départ{route.trips > 1 ? 's' : ''} · dès {formatFcfa(route.minPrice)}
                        </span>
                      </span>
                      <ArrowRight
                        className="size-4 shrink-0 text-muted transition-[transform,color] group-hover:translate-x-0.5 group-hover:text-primary-ink"
                        aria-hidden
                      />
                    </button>
                  </m.li>
                ))}
              </m.ul>
            )}
          </section>
        ) : null}

        {/* --- Promesses produit --- */}
        <section aria-label="Ce qui distingue Ekuiseo" className="mt-12">
          <m.div
            variants={listContainer}
            initial="hidden"
            animate="show"
            className="grid gap-3 md:grid-cols-3"
          >
            {PROMISES.map((promise) => (
              <m.div key={promise.title} variants={listItem}>
                <Card className="h-full p-5">
                  <span className={`flex size-10 items-center justify-center rounded-[var(--radius-control)] ${promise.tone}`}>
                    <promise.icon className="size-5" aria-hidden />
                  </span>
                  <h2 className="mt-4 font-display text-title font-bold">{promise.title}</h2>
                  <p className="mt-1.5 text-body leading-relaxed text-ink-2">{promise.text}</p>
                </Card>
              </m.div>
            ))}
          </m.div>
        </section>
      </PageContainer>
    </div>
  )
}
