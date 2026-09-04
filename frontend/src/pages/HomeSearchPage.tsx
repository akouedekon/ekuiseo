import { motion } from 'motion/react'
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
import { usePopularRoutes, useRecurringTrips } from '@/hooks/useTrips'
import { BENIN_CITIES, findCityByLabel, type CityOption } from '@/lib/cities'
import { formatFcfa } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'
import type { PopularRouteResponse } from '@/api/extended'
import type { TripType } from '@/api/types'

const WEEKDAY_LETTERS = ['L', 'M', 'M', 'J', 'V', 'S', 'D']
const WEEKDAY_NAMES = ['lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi', 'dimanche']


/** Promesses produit : trois, pas plus, chacune avec sa teinte de signal. */
const PROMISES = [
  {
    icon: Wallet,
    tone: 'bg-success-soft text-success-ink',
    title: 'Paiement en deux temps',
    text: "Un acompte en mobile money, à partir de 1 000 FCFA, bloque la place. Le solde se règle en espèces au conducteur, à bord.",
  },
  {
    icon: ShieldCheck,
    tone: 'bg-primary-soft text-primary-ink',
    title: 'Conducteurs vérifiés',
    text: "Pièce d'identité contrôlée, numéro confirmé par SMS, avis publics après chaque trajet.",
  },
  {
    icon: WifiOff,
    tone: 'bg-accent-soft text-accent-ink',
    title: "Pensé pour le réseau d'ici",
    text: 'Vos résultats restent en mémoire et vos actions repartent dès que la connexion revient.',
  },
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
  // Axes reellement proposes en ce moment : la liste vient du serveur, jamais d'une constante.
  const popular = usePopularRoutes(4)
  const popularRoutes = popular.data?.data ?? []

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

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    setTouched(true)
    if (!origin || !destination) return
    goTo(origin, destination)
  }

  const goToRoute = (fromLabel: string, toLabel: string) => {
    const from = findCityByLabel(fromLabel) ?? BENIN_CITIES[0]
    const to = findCityByLabel(toLabel) ?? BENIN_CITIES[1]
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
      {/* Nappe lumineuse et grille pointillee : la seule matiere decorative de l'application. */}
      <div aria-hidden className="ek-glow pointer-events-none absolute inset-x-0 top-0 h-[520px]" />
      <div aria-hidden className="ek-dots pointer-events-none absolute inset-x-0 top-0 h-[420px]" />

      <PageContainer width="lg" className="relative pb-12 sm:pt-12">
        {/* --- Hero --- */}
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4 }}
          className="mx-auto max-w-2xl text-center"
        >
          <Badge tone="indigo" className="mb-4 gap-1.5 px-2.5 py-1">
            <Sparkles aria-hidden />
            Covoiturage au Bénin · interurbain et quotidien
          </Badge>
          <h1 className="headline text-[36px] sm:text-hero">
            Partagez la route,
            <br />
            partagez le prix.
          </h1>
          <p className="mx-auto mt-4 max-w-xl text-base leading-relaxed text-ink-2 sm:text-lead">
            Cotonou, Bohicon, Parakou, Porto-Novo, Lomé… Un acompte en mobile money bloque votre place, le reste se
            règle en espèces à bord — ou tout en ligne, à votre choix.
          </p>
        </motion.div>

        {/* --- Panneau de recherche --- */}
        <motion.div
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
                {/* Inversion : centree sur la couture des deux champs. */}
                <button
                  type="button"
                  onClick={swap}
                  aria-label="Inverser le départ et l'arrivée"
                  className="absolute left-1/2 top-[40px] z-10 hidden size-9 -translate-x-1/2 items-center justify-center rounded-full border border-rule-strong bg-surface text-ink-2 shadow-e1 transition-[transform,color] hover:rotate-180 hover:text-primary-ink sm:flex"
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
                  <span className="text-label font-medium text-ink-2">Places</span>
                  <div className="flex h-12 items-center">
                    <Stepper value={seats} onChange={setSeats} min={1} max={8} label="places" />
                  </div>
                </div>
              </div>

              <Button type="submit" size="lg" block className="mt-5">
                <Search aria-hidden />
                Rechercher un trajet
              </Button>
            </form>
          </Card>
        </motion.div>

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
                  Connectez-vous pour enregistrer votre navette et retrouver les départs correspondants en un geste.
                </p>
                <Button asChild variant="secondary" size="sm">
                  <Link to="/login">Se connecter</Link>
                </Button>
              </Card>
            ) : recurring.isPending ? (
              <Card className="p-5">
                <Skeleton className="h-4 w-40" />
                <Skeleton className="mt-3 h-8 w-full" />
              </Card>
            ) : recurring.data && recurring.data.data.length > 0 ? (
              <motion.div variants={listContainer} initial="hidden" animate="show" className="space-y-3">
                {recurring.data.data.map((item) => (
                  <motion.div key={item.id} variants={listItem}>
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

                      <div className="mt-3 flex gap-1" role="list" aria-label="Jours de circulation">
                        {WEEKDAY_LETTERS.map((letter, index) => {
                          const active = item.weekdays.includes(index + 1)
                          return (
                            <span
                              key={index}
                              role="listitem"
                              aria-label={`${WEEKDAY_NAMES[index]} : ${active ? 'oui' : 'non'}`}
                              className={
                                active
                                  ? 'flex size-7 items-center justify-center rounded-[var(--radius-chip)] bg-primary text-caption font-bold text-on-primary'
                                  : 'flex size-7 items-center justify-center rounded-[var(--radius-chip)] bg-surface-2 text-caption font-semibold text-muted'
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
                        className="mt-4"
                        onClick={() => goToRoute(item.originLabel, item.destLabel)}
                      >
                        Voir les départs
                      </Button>
                    </Card>
                  </motion.div>
                ))}
              </motion.div>
            ) : (
              <Card className="p-5 text-body text-muted">
                Aucune navette enregistrée. Lancez une recherche quotidienne : nous vous proposerons de la mémoriser.
              </Card>
            )}
          </section>
        ) : null}

        {/* --- Axes proposes en ce moment (donnees serveur) --- */}
        {popular.isPending || popularRoutes.length > 0 ? (
          <section aria-labelledby="popular-title" className="mt-12">
            <SectionTitle>
              <span id="popular-title">Départs proposés en ce moment</span>
            </SectionTitle>
            {popular.isPending ? (
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                {[0, 1, 2, 3].map((i) => (
                  <Skeleton key={i} className="h-[72px] rounded-[var(--radius-card)]" />
                ))}
              </div>
            ) : (
              <motion.ul
                variants={listContainer}
                initial="hidden"
                animate="show"
                className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4"
              >
                {popularRoutes.map((route) => (
                  <motion.li key={`${route.originLabel}-${route.destLabel}`} variants={listItem}>
                    <button
                      type="button"
                      onClick={() => goToPopular(route)}
                      className="ek-lift group flex min-h-[72px] w-full items-center gap-3 rounded-[var(--radius-card)] border border-rule bg-surface p-4 text-left shadow-e1"
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
                  </motion.li>
                ))}
              </motion.ul>
            )}
          </section>
        ) : null}

        {/* --- Promesses produit --- */}
        <section aria-label="Ce qui distingue Ekuiseo" className="mt-12">
          <motion.div
            variants={listContainer}
            initial="hidden"
            animate="show"
            className="grid gap-3 md:grid-cols-3"
          >
            {PROMISES.map((promise) => (
              <motion.div key={promise.title} variants={listItem}>
                <Card className="h-full p-5">
                  <span className={`flex size-10 items-center justify-center rounded-[var(--radius-control)] ${promise.tone}`}>
                    <promise.icon className="size-5" aria-hidden />
                  </span>
                  <h2 className="mt-4 font-display text-title font-bold">{promise.title}</h2>
                  <p className="mt-1.5 text-body leading-relaxed text-ink-2">{promise.text}</p>
                </Card>
              </motion.div>
            ))}
          </motion.div>
        </section>
      </PageContainer>
    </div>
  )
}
