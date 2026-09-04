import { motion } from 'motion/react'
import {
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
} from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { SegmentedToggle } from '@/components/ui/tabs'
import { Skeleton, Stepper } from '@/components/ui/misc'
import { CityAutocomplete } from '@/components/trip/CityAutocomplete'
import { PageContainer, SectionTitle } from '@/components/layout/PageContainer'
import { isAuthenticated } from '@/hooks/useAuth'
import { useRecurringTrips } from '@/hooks/useTrips'
import { BENIN_CITIES, findCityByLabel, type CityOption } from '@/lib/cities'
import { formatFcfa } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'
import type { TripType } from '@/api/types'

const WEEKDAY_LETTERS = ['L', 'M', 'M', 'J', 'V', 'S', 'D']

/** Axes les plus demandes, en acces direct depuis l'accueil. */
const POPULAR_ROUTES: { from: string; to: string; price: number }[] = [
  { from: 'Cotonou', to: 'Bohicon', price: 3500 },
  { from: 'Cotonou', to: 'Porto-Novo', price: 1500 },
  { from: 'Cotonou', to: 'Parakou', price: 9000 },
  { from: 'Abomey-Calavi', to: 'Cotonou', price: 1000 },
]

function todayIso(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export function HomeSearchPage() {
  const navigate = useNavigate()
  const authed = isAuthenticated()

  const [tripType, setTripType] = useState<TripType>('INTERURBAIN')
  const [origin, setOrigin] = useState<CityOption | null>(null)
  const [destination, setDestination] = useState<CityOption | null>(null)
  const [date, setDate] = useState(todayIso())
  const [seats, setSeats] = useState(1)
  const [touched, setTouched] = useState(false)

  const recurring = useRecurringTrips(authed && tripType === 'QUOTIDIEN')

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

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    setTouched(true)
    if (!origin || !destination) return
    const params = new URLSearchParams({
      from: origin.label,
      fromLat: String(origin.lat),
      fromLng: String(origin.lng),
      to: destination.label,
      toLat: String(destination.lat),
      toLng: String(destination.lng),
      date,
      seats: String(seats),
      type: tripType,
    })
    navigate(`/search?${params.toString()}`)
  }

  const goToRoute = (fromLabel: string, toLabel: string) => {
    const from = findCityByLabel(fromLabel) ?? BENIN_CITIES[0]
    const to = findCityByLabel(toLabel) ?? BENIN_CITIES[1]
    setOrigin(from)
    setDestination(to)
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
    navigate(`/search?${params.toString()}`)
  }

  return (
    <PageContainer width="lg" className="pb-10">
      {/* --- Accroche --- */}
      <motion.div
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35 }}
        className="mb-5 max-w-xl"
      >
        <h1 className="headline text-[32px] sm:text-[42px]">
          Partagez la route,
          <br />
          partagez le prix.
        </h1>
        <p className="mt-2 text-[15px] leading-relaxed text-ink-2">
          Trajets interurbains et navettes quotidiennes partout au Bénin. Acompte à partir de 1 000 FCFA en mobile
          money, le reste en espèces à bord — ou tout en ligne, à votre choix.
        </p>
      </motion.div>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-6">
          {/* --- Formulaire de recherche --- */}
          <Card className="overflow-visible p-4 shadow-e2 sm:p-5">
            <form onSubmit={submit} noValidate>
              <SegmentedToggle
                label="Type de trajet"
                value={tripType}
                onValueChange={setTripType}
                className="mb-4"
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
                {/* Inversion : centree sur la couture des deux champs. */}
                <button
                  type="button"
                  onClick={swap}
                  aria-label="Inverser le départ et l'arrivée"
                  className="absolute left-1/2 top-[38px] z-10 hidden size-9 -translate-x-1/2 items-center justify-center rounded-full border border-rule-strong bg-surface text-ink-2 shadow-e1 transition-transform hover:rotate-180 hover:text-[var(--indigo)] sm:flex"
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
                  onChange={(event) => setDate(event.target.value)}
                  leading={<CalendarDays />}
                  className="h-12"
                />
                <div className="flex flex-col gap-1.5">
                  <span className="text-[13px] font-medium text-ink-2">Places</span>
                  <div className="flex h-12 items-center">
                    <Stepper value={seats} onChange={setSeats} min={1} max={8} label="places" />
                  </div>
                </div>
              </div>

              <Button type="submit" size="lg" block className="mt-4">
                <Search className="size-5" aria-hidden />
                Rechercher
              </Button>
            </form>
          </Card>

          {/* --- Trajet de la semaine (mode quotidien) --- */}
          {tripType === 'QUOTIDIEN' ? (
            <section aria-labelledby="recurring-title">
              <SectionTitle>
                <span id="recurring-title">Votre trajet de la semaine</span>
              </SectionTitle>
              {!authed ? (
                <Card className="flex flex-col items-start gap-3 p-4 sm:flex-row sm:items-center">
                  <Sparkles className="size-5 shrink-0 text-[var(--indigo)]" aria-hidden />
                  <p className="flex-1 text-[14px] text-ink-2">
                    Connectez-vous pour enregistrer votre navette et retrouver les départs correspondants en un geste.
                  </p>
                  <Button asChild variant="secondary" size="sm">
                    <Link to="/login">Se connecter</Link>
                  </Button>
                </Card>
              ) : recurring.isPending ? (
                <Card className="p-4">
                  <Skeleton className="h-4 w-40" />
                  <Skeleton className="mt-3 h-8 w-full" />
                </Card>
              ) : recurring.data && recurring.data.data.length > 0 ? (
                <motion.div variants={listContainer} initial="hidden" animate="show" className="space-y-3">
                  {recurring.data.data.map((item) => (
                    <motion.div key={item.id} variants={listItem}>
                      <Card className="p-4">
                        <div className="flex flex-wrap items-start justify-between gap-3">
                          <div className="min-w-0">
                            <p className="font-display text-[16px] font-bold leading-tight">
                              {item.originLabel} → {item.destLabel}
                            </p>
                            <p className="tnum mt-1 flex items-center gap-1.5 text-[13px] text-muted">
                              <Clock className="size-3.5" aria-hidden />
                              {item.departureTime} · {item.seats} place{item.seats > 1 ? 's' : ''}
                            </p>
                          </div>
                          <Badge tone={item.matchesAvailable > 0 ? 'success' : 'neutral'}>
                            {item.matchesAvailable > 0
                              ? `${item.matchesAvailable} départs disponibles`
                              : 'Aucun départ'}
                          </Badge>
                        </div>

                        {/* Jours actifs : lecture immediate de la recurrence. */}
                        <div className="mt-3 flex gap-1" role="list" aria-label="Jours de circulation">
                          {WEEKDAY_LETTERS.map((letter, index) => {
                            const active = item.weekdays.includes(index + 1)
                            return (
                              <span
                                key={index}
                                role="listitem"
                                aria-label={`${['lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi', 'dimanche'][index]} : ${active ? 'oui' : 'non'}`}
                                className={
                                  active
                                    ? 'flex size-7 items-center justify-center rounded-[var(--radius-chip)] bg-[var(--indigo)] text-[12px] font-bold text-[var(--indigo-contrast)]'
                                    : 'flex size-7 items-center justify-center rounded-[var(--radius-chip)] bg-[var(--surface-calm)] text-[12px] font-semibold text-muted'
                                }
                              >
                                {letter}
                              </span>
                            )
                          })}
                        </div>

                        <Button
                          variant="outlineBrand"
                          size="sm"
                          block
                          className="mt-3"
                          onClick={() => goToRoute(item.originLabel, item.destLabel)}
                        >
                          Voir les départs
                        </Button>
                      </Card>
                    </motion.div>
                  ))}
                </motion.div>
              ) : (
                <Card className="p-4 text-[14px] text-muted">
                  Aucune navette enregistrée. Lancez une recherche quotidienne : nous vous proposerons de la mémoriser.
                </Card>
              )}
            </section>
          ) : null}

          {/* --- Axes populaires --- */}
          <section aria-labelledby="popular-title">
            <SectionTitle>
              <span id="popular-title">Axes fréquentés</span>
            </SectionTitle>
            <motion.ul variants={listContainer} initial="hidden" animate="show" className="grid gap-2 sm:grid-cols-2">
              {POPULAR_ROUTES.map((route) => (
                <motion.li key={`${route.from}-${route.to}`} variants={listItem}>
                  <button
                    type="button"
                    onClick={() => goToRoute(route.from, route.to)}
                    className="flex min-h-[56px] w-full items-center gap-3 rounded-[var(--radius-card)] border border-rule bg-surface px-4 py-2.5 text-left transition-all hover:-translate-y-0.5 hover:shadow-e2 active:translate-y-0"
                  >
                    <TrendingUp className="size-4 shrink-0 text-muted" aria-hidden />
                    <span className="min-w-0 flex-1 truncate text-[14px] font-semibold">
                      {route.from} → {route.to}
                    </span>
                    <span className="tnum shrink-0 text-[13px] font-semibold text-ink-2">
                      dès {formatFcfa(route.price)}
                    </span>
                  </button>
                </motion.li>
              ))}
            </motion.ul>
          </section>
        </div>

        {/* --- Colonne d'appui : promesses produit --- */}
        <aside className="space-y-3">
          <Card className="p-4">
            <span className="flex size-9 items-center justify-center rounded-[var(--radius-control)] bg-[var(--vert-soft)] text-[var(--vert)]">
              <Wallet className="size-[18px]" aria-hidden />
            </span>
            <h2 className="mt-3 font-display text-[16px] font-bold">Paiement en deux temps</h2>
            <p className="mt-1 text-[14px] leading-relaxed text-ink-2">
              Un acompte en mobile money — à partir de 1 000 FCFA — bloque la place. Le solde se règle en espèces au
              conducteur, à bord. Vous pouvez aussi tout payer en ligne.
            </p>
          </Card>
          <Card className="p-4">
            <span className="flex size-9 items-center justify-center rounded-[var(--radius-control)] bg-[var(--indigo-soft)] text-[var(--indigo)]">
              <ShieldCheck className="size-[18px]" aria-hidden />
            </span>
            <h2 className="mt-3 font-display text-[16px] font-bold">Conducteurs vérifiés</h2>
            <p className="mt-1 text-[14px] leading-relaxed text-ink-2">
              Pièce d'identité contrôlée, numéro confirmé par SMS, avis publics après chaque trajet.
            </p>
          </Card>
          <Card className="p-4">
            <span className="flex size-9 items-center justify-center rounded-[var(--radius-control)] bg-[var(--ocre-soft)] text-[var(--ocre-ink)]">
              <Clock className="size-[18px]" aria-hidden />
            </span>
            <h2 className="mt-3 font-display text-[16px] font-bold">Pensé pour le réseau d'ici</h2>
            <p className="mt-1 text-[14px] leading-relaxed text-ink-2">
              L'application garde vos résultats en mémoire et renvoie vos actions dès que la connexion revient.
            </p>
          </Card>
        </aside>
      </div>
    </PageContainer>
  )
}
