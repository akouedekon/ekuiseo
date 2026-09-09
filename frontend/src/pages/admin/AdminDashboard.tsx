import { m } from 'motion/react'
import { AlertTriangle, ArrowRight, BadgeCheck, Banknote, CalendarRange, Target, Wallet, type LucideIcon } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip as RechartsTooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { Card } from '@/components/ui/card'
import { Progress } from '@/components/ui/misc'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { ErrorState, StatSkeleton } from '@/components/ui/states'
import { PageMeta } from '@/components/layout/PageMeta'
import { useAdminLiquidity, useAdminOverview, useAdminRetention, useAdminStats } from '@/hooks/useAdmin'
import { cn } from '@/lib/cn'
import { describeError } from '@/lib/errors'
import { formatFcfa, formatFcfaCompact, formatFromNow } from '@/lib/format'
import { listContainer } from '@/lib/motion'
import type { BookingStatus } from '@/api/types'
import { CHART, formatHours, formatPercent, pointsDelta, ratioDelta, formatRatio, relativeDelta } from './adminMetrics'
import { ChartTooltip, DeltaBadge, StatTile } from './AdminWidgets'

const STATUS_LABEL: Record<BookingStatus, string> = {
  CONFIRMED: 'Confirmées',
  PENDING_PAYMENT: 'Acompte attendu',
  PENDING_DRIVER_APPROVAL: 'Accord conducteur attendu',
  COMPLETED: 'Terminées',
  CANCELLED_BY_PASSENGER: 'Annul. passager',
  CANCELLED_BY_DRIVER: 'Annul. conducteur',
  NO_SHOW: 'Non présentés',
  DRIVER_NO_SHOW: 'Conducteur absent',
  EXPIRED: 'Expirées (acompte non reçu)',
}

const STATUS_COLOR: Record<BookingStatus, string> = {
  CONFIRMED: CHART.vert,
  PENDING_PAYMENT: CHART.ocre,
  // Acompte encaisse mais conducteur pas encore prononce : meme famille d'attente que l'acompte.
  PENDING_DRIVER_APPROVAL: CHART.ocre,
  // Terminee = graphite : la reservation a vecu, elle n'appelle plus d'action.
  COMPLETED: CHART.indigo,
  CANCELLED_BY_PASSENGER: CHART.vermillon,
  CANCELLED_BY_DRIVER: CHART.vermillon,
  NO_SHOW: CHART.muted,
  // Conducteur absent (V21) : une defaillance conducteur, meme famille que ses annulations.
  DRIVER_NO_SHOW: CHART.vermillon,
  // Expiree = acompte jamais recu : une place liberee, pas une perte pour la plateforme.
  EXPIRED: CHART.muted,
}

/**
 * Ordre d'affichage volontaire (CLAUDE.md, « Comment les presenter ») : la
 * metrique nord et sa trajectoire vers le seuil de 2 000 places par mois, puis
 * la liquidite en quatre chiffres, puis seulement le volume. Le detail de la
 * liquidite vit sur sa propre page (/admin/liquidity).
 */
export function AdminDashboard() {
  const [days, setDays] = useState(30)
  const stats = useAdminStats(days)
  const liquidity = useAdminLiquidity(days)
  const retention = useAdminRetention(days)

  if (stats.isError) return <ErrorState description={describeError(stats.error)} onRetry={() => stats.refetch()} />

  const data = stats.data
  const liq = liquidity.data
  const ret = retention.data

  const series =
    data?.series.map((row) => ({
      ...row,
      // Etiquette courte pour l'axe X : « 12/09 ».
      label: `${row.date.slice(8, 10)}/${row.date.slice(5, 7)}`,
    })) ?? []

  const weekly =
    liq?.northStar.weekly.map((row) => ({
      ...row,
      label: `${row.weekStart.slice(8, 10)}/${row.weekStart.slice(5, 7)}`,
    })) ?? []
  // Objectif hebdomadaire equivalent au seuil mensuel, pour la ligne de reference.
  const weeklyTarget = liq ? Math.round((liq.northStar.monthlyTarget * 7) / 30) : 0

  return (
    <div>
      <PageMeta title="Tableau de bord · Back-office" noindex />

      <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="font-display text-heading font-extrabold tracking-[-0.03em]">Vue d'ensemble</h2>
          <p className="mt-0.5 text-label text-muted">Métrique nord, liquidité, rétention, puis volume.</p>
        </div>
        <Select value={String(days)} onValueChange={(value) => setDays(Number(value))}>
          <SelectTrigger className="h-10 w-auto min-w-[150px] gap-2 text-body" aria-label="Période analysée">
            <CalendarRange className="size-4 text-muted" aria-hidden />
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="7">7 derniers jours</SelectItem>
            <SelectItem value="30">30 derniers jours</SelectItem>
            <SelectItem value="90">90 derniers jours</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* --- Files d'attente : ce qui attend une decision aujourd'hui --- */}
      <QueuesBlock />

      {/* --- Metrique nord : places confirmees vs seuil de viabilite --- */}
      <Card className="relative overflow-hidden p-5 sm:p-6">
        <span aria-hidden className="ek-glow pointer-events-none absolute inset-x-0 top-0 h-full" />
        {liquidity.isError ? (
          <ErrorState
            title="Métrique nord indisponible"
            description={describeError(liquidity.error)}
            onRetry={() => liquidity.refetch()}
          />
        ) : liquidity.isPending || !liq ? (
          <div className="shimmer h-[168px] rounded-[var(--radius-control)]" />
        ) : (
          <div className="relative grid gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
            <div>
              <p className="flex items-center gap-1.5 text-label font-medium text-muted">
                <span className="flex size-6 items-center justify-center rounded-[var(--radius-chip)] bg-primary-soft text-primary-ink">
                  <Target className="size-3.5" aria-hidden />
                </span>
                Places confirmées sur la période
              </p>
              <p className="tnum mt-3 font-display text-hero font-extrabold leading-none tracking-[-0.035em] text-ink">
                {liq.northStar.confirmedSeats.toLocaleString('fr-FR')}
              </p>
              <p className="mt-2 flex flex-wrap items-center gap-x-1.5 text-label">
                <DeltaBadge
                  delta={relativeDelta(liq.northStar.confirmedSeats, liq.northStar.previousConfirmedSeats)}
                  unit="%"
                />
                <span className="text-muted">vs période précédente</span>
              </p>
              <div className="mt-4">
                <div className="flex items-baseline justify-between gap-3 text-label">
                  <span className="text-muted">Rythme mensuel</span>
                  <span className="tnum font-semibold">
                    {Math.round(liq.northStar.monthlyPace).toLocaleString('fr-FR')} /{' '}
                    {liq.northStar.monthlyTarget.toLocaleString('fr-FR')} places
                  </span>
                </div>
                <Progress
                  value={Math.min(100, liq.northStar.progressPercent)}
                  tone={liq.northStar.progressPercent >= 100 ? 'success' : liq.northStar.progressPercent >= 50 ? 'accent' : 'danger'}
                  className="mt-1.5"
                  aria-label="Progression vers le seuil de viabilité"
                />
                {/* Le seuil vient de l'API (audit F311) : jamais recopie en dur dans la phrase. */}
                <p className="mt-1.5 text-caption text-muted">
                  {formatPercent(liq.northStar.progressPercent, 0)} du seuil de viabilité (
                  {liq.northStar.monthlyTarget.toLocaleString('fr-FR')} places par mois). En dessous, le projet paie
                  l'hébergement, pas un salaire.
                </p>
              </div>
            </div>
            <div className="h-[168px] w-full">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={weekly} margin={{ top: 8, right: 4, bottom: 0, left: -18 }}>
                  <CartesianGrid stroke={CHART.rule} vertical={false} />
                  <XAxis
                    dataKey="label"
                    tick={{ fill: CHART.muted, fontSize: 11 }}
                    tickLine={false}
                    axisLine={{ stroke: CHART.rule }}
                  />
                  <YAxis tick={{ fill: CHART.muted, fontSize: 11 }} tickLine={false} axisLine={false} width={44} />
                  <RechartsTooltip content={<ChartTooltip />} cursor={{ fill: 'var(--surface-2)' }} />
                  <ReferenceLine
                    y={weeklyTarget}
                    stroke={CHART.ocre}
                    strokeDasharray="4 4"
                    label={{ value: 'Seuil / semaine', fill: CHART.muted, fontSize: 11, position: 'insideTopRight' }}
                  />
                  <Bar dataKey="seats" name="Places confirmées" fill={CHART.indigo} radius={[4, 4, 0, 0]} barSize={22} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </div>
        )}
      </Card>

      {/* --- Liquidite : quatre chiffres, le detail sur sa page --- */}
      <div className="mb-2 mt-5 flex items-baseline justify-between gap-3">
        <h2 className="font-display text-base font-bold">Liquidité</h2>
        <Link
          to="/admin/liquidity"
          className="inline-flex items-center gap-1 text-label font-semibold text-primary-ink hover:underline"
        >
          Axes en pénurie et remplissage
          <ArrowRight className="size-3.5" aria-hidden />
        </Link>
      </div>
      {liquidity.isError ? (
        <ErrorState title="Liquidité indisponible" description={describeError(liquidity.error)} onRetry={() => liquidity.refetch()} />
      ) : liquidity.isPending || !liq ? (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <StatSkeleton key={i} />
          ))}
        </div>
      ) : (
        <m.div
          variants={listContainer}
          initial="hidden"
          animate="show"
          className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4"
        >
          <StatTile
            label="Recherches abouties"
            value={formatPercent(liq.current.searchSuccessRate)}
            delta={pointsDelta(liq.current.searchSuccessRate, liq.previous.searchSuccessRate)}
            deltaUnit="pts"
            hint={`${liq.current.searches.toLocaleString('fr-FR')} recherches`}
          />
          <StatTile
            label="Taux de remplissage"
            value={formatPercent(liq.current.fillRate)}
            delta={pointsDelta(liq.current.fillRate, liq.previous.fillRate)}
            deltaUnit="pts"
            hint={`${liq.current.seatsBooked.toLocaleString('fr-FR')} / ${liq.current.seatsPublished.toLocaleString('fr-FR')} places`}
          />
          <StatTile
            label="Trajets orphelins"
            value={formatPercent(liq.current.orphanRate)}
            delta={pointsDelta(liq.current.orphanRate, liq.previous.orphanRate)}
            deltaUnit="pts"
            lowerIsBetter
            hint={`${liq.current.orphanTrips.toLocaleString('fr-FR')} trajets sans réservation`}
          />
          <StatTile
            label="Délai avant 1re réservation"
            value={
              liq.current.medianHoursToFirstBooking == null ? '—' : formatHours(liq.current.medianHoursToFirstBooking)
            }
            delta={
              liq.current.medianHoursToFirstBooking == null || liq.previous.medianHoursToFirstBooking == null
                ? null
                : Math.round((liq.current.medianHoursToFirstBooking - liq.previous.medianHoursToFirstBooking) * 10) / 10
            }
            deltaUnit="h"
            lowerIsBetter
            hint="Médiane, publication → première réservation"
          />
        </m.div>
      )}

      {/* --- Retention : le produit retient-il, et la these du quotidien tient-elle ? --- */}
      <div className="mb-2 mt-5 flex items-baseline justify-between gap-3">
        <h2 className="font-display text-base font-bold">Rétention</h2>
        <Link
          to="/admin/retention"
          className="inline-flex items-center gap-1 text-label font-semibold text-primary-ink hover:underline"
        >
          Cohortes, paiement et panier
          <ArrowRight className="size-3.5" aria-hidden />
        </Link>
      </div>
      {retention.isError ? (
        <ErrorState
          title="Rétention indisponible"
          description={describeError(retention.error)}
          onRetry={() => retention.refetch()}
        />
      ) : retention.isPending || !ret ? (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <StatSkeleton key={i} />
          ))}
        </div>
      ) : (
        <m.div variants={listContainer} initial="hidden" animate="show" className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          <StatTile
            label="Conducteurs qui republient (S+1)"
            value={formatRatio(ret.driverRetentionW1)}
            delta={ratioDelta(ret.driverRetentionW1, ret.previous.driverRetentionW1)}
            deltaUnit="pts"
            hint="Un conducteur sans passager ne republie pas : ce chiffre le dit avant les plaintes."
          />
          <StatTile
            label="Passagers qui reviennent (30 j)"
            value={formatRatio(ret.passengerRetention30d)}
            delta={ratioDelta(ret.passengerRetention30d, ret.previous.passengerRetention30d)}
            deltaUnit="pts"
            hint="Réservent à nouveau sous 30 jours"
          />
          <StatTile
            label="Part du mode quotidien"
            value={formatRatio(ret.dailyModeShare)}
            delta={ratioDelta(ret.dailyModeShare, ret.previous.dailyModeShare)}
            deltaUnit="pts"
            hint={`${ret.activeRecurringTemplates.toLocaleString('fr-FR')} navettes actives · la thèse du modèle économique`}
          />
          <StatTile
            label="Réservation → acompte encaissé"
            value={formatRatio(ret.bookingToDepositRate)}
            delta={ratioDelta(ret.bookingToDepositRate, ret.previous.bookingToDepositRate)}
            deltaUnit="pts"
            hint={
              ret.expiredBookingShare == null
                ? 'Réservations en mobile money'
                : `${formatRatio(ret.expiredBookingShare)} expirent faute d'acompte sous 20 min`
            }
          />
        </m.div>
      )}

      {/* --- Volume --- */}
      <h2 className="mb-2 mt-5 font-display text-base font-bold">Volume</h2>
      {stats.isPending || !data ? (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <StatSkeleton key={i} />
          ))}
        </div>
      ) : (
        <m.div
          variants={listContainer}
          initial="hidden"
          animate="show"
          className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4"
        >
          <StatTile label="Trajets publiés" value={data.totals.trips.toLocaleString('fr-FR')} delta={data.deltas.trips} />
          <StatTile
            label="Réservations"
            value={data.totals.bookings.toLocaleString('fr-FR')}
            delta={data.deltas.bookings}
          />
          <StatTile
            label="Volume d'affaires"
            value={`${formatFcfaCompact(data.totals.gmv)} FCFA`}
            title={formatFcfa(data.totals.gmv)}
            delta={data.deltas.gmv}
          />
          <StatTile
            label="Revenus Ekuiseo"
            value={`${formatFcfaCompact(data.totals.revenue)} FCFA`}
            title={formatFcfa(data.totals.revenue)}
            delta={data.deltas.revenue}
          />
        </m.div>
      )}

      {/* --- Volume : trajets et reservations --- */}
      <Card className="mt-4 p-4">
        <h2 className="font-display text-base font-bold">Activité quotidienne</h2>
        <p className="mb-3 text-label text-muted">Trajets publiés et réservations créées, par jour</p>
        <div className="h-[240px] w-full">
          {stats.isPending ? (
            <div className="shimmer size-full rounded-[var(--radius-control)]" />
          ) : (
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={series} margin={{ top: 4, right: 4, bottom: 0, left: -18 }}>
                <defs>
                  <linearGradient id="fill-trips" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={CHART.indigo} stopOpacity={0.22} />
                    <stop offset="100%" stopColor={CHART.indigo} stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid stroke={CHART.rule} vertical={false} />
                <XAxis
                  dataKey="label"
                  tick={{ fill: CHART.muted, fontSize: 11 }}
                  tickLine={false}
                  axisLine={{ stroke: CHART.rule }}
                  minTickGap={24}
                />
                <YAxis tick={{ fill: CHART.muted, fontSize: 11 }} tickLine={false} axisLine={false} width={44} />
                <RechartsTooltip content={<ChartTooltip />} />
                <Legend wrapperStyle={{ fontSize: 12, paddingTop: 8 }} />
                <Area
                  type="monotone"
                  dataKey="bookings"
                  name="Réservations"
                  stroke={CHART.indigo}
                  strokeWidth={2}
                  fill="url(#fill-trips)"
                />
                <Line type="monotone" dataKey="trips" name="Trajets" stroke={CHART.ocre} strokeWidth={2} dot={false} />
              </AreaChart>
            </ResponsiveContainer>
          )}
        </div>
      </Card>

      <div className="mt-4 grid gap-4 xl:grid-cols-2">
        {/* --- Volume d'affaires et revenus --- */}
        <Card className="p-4">
          <h2 className="font-display text-base font-bold">Volume et revenus</h2>
          <p className="mb-3 text-label text-muted">En FCFA, par jour</p>
          <div className="h-[220px] w-full">
            {stats.isPending ? (
              <div className="shimmer size-full rounded-[var(--radius-control)]" />
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={series} margin={{ top: 4, right: 4, bottom: 0, left: -8 }}>
                  <CartesianGrid stroke={CHART.rule} vertical={false} />
                  <XAxis
                    dataKey="label"
                    tick={{ fill: CHART.muted, fontSize: 11 }}
                    tickLine={false}
                    axisLine={{ stroke: CHART.rule }}
                    minTickGap={28}
                  />
                  <YAxis
                    tick={{ fill: CHART.muted, fontSize: 11 }}
                    tickLine={false}
                    axisLine={false}
                    width={52}
                    tickFormatter={(value: number) => formatFcfaCompact(value)}
                  />
                  <RechartsTooltip content={<ChartTooltip money />} />
                  <Legend wrapperStyle={{ fontSize: 12, paddingTop: 8 }} />
                  <Line
                    type="monotone"
                    dataKey="gmv"
                    name="Volume d'affaires"
                    stroke={CHART.indigo}
                    strokeWidth={2}
                    dot={false}
                  />
                  <Line
                    type="monotone"
                    dataKey="revenue"
                    name="Revenus"
                    stroke={CHART.vert}
                    strokeWidth={2}
                    dot={false}
                  />
                </LineChart>
              </ResponsiveContainer>
            )}
          </div>
        </Card>

        {/* --- Repartition par statut --- */}
        <Card className="p-4">
          <h2 className="font-display text-base font-bold">Réservations par statut</h2>
          <p className="mb-3 text-label text-muted">Sur la période</p>
          <div className="h-[220px] w-full">
            {stats.isPending || !data ? (
              <div className="shimmer size-full rounded-[var(--radius-control)]" />
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart
                  data={data.bookingsByStatus.map((row) => ({
                    ...row,
                    label: STATUS_LABEL[row.status],
                  }))}
                  layout="vertical"
                  margin={{ top: 0, right: 12, bottom: 0, left: 0 }}
                >
                  <CartesianGrid stroke={CHART.rule} horizontal={false} />
                  <XAxis type="number" tick={{ fill: CHART.muted, fontSize: 11 }} tickLine={false} axisLine={false} />
                  <YAxis
                    type="category"
                    dataKey="label"
                    tick={{ fill: CHART.muted, fontSize: 11 }}
                    tickLine={false}
                    axisLine={false}
                    width={118}
                  />
                  <RechartsTooltip content={<ChartTooltip />} cursor={{ fill: 'var(--surface-2)' }} />
                  <Bar dataKey="count" name="Réservations" radius={[0, 4, 4, 0]} barSize={16}>
                    {data.bookingsByStatus.map((row) => (
                      <Cell key={row.status} fill={STATUS_COLOR[row.status]} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            )}
          </div>
        </Card>
      </div>

      {/* --- Axes les plus actifs --- */}
      <Card className="mt-4">
        <div className="px-4 pt-4">
          <h2 className="font-display text-base font-bold">Axes les plus actifs</h2>
          <p className="text-label text-muted">Classés par volume d'affaires</p>
        </div>
        <div className="mt-3 overflow-x-auto">
          <table className="w-full min-w-[420px] text-body">
            <thead>
              <tr className="border-y border-rule bg-surface-2 text-left text-caption uppercase tracking-wide text-muted">
                <th scope="col" className="px-4 py-2 font-semibold">Axe</th>
                <th scope="col" className="px-4 py-2 text-right font-semibold">Trajets</th>
                <th scope="col" className="px-4 py-2 text-right font-semibold">Volume</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-rule">
              {data && data.topRoutes.length === 0 ? (
                <tr>
                  <td colSpan={3} className="px-4 py-6 text-center text-label text-muted">
                    Aucun trajet publié sur la période.
                  </td>
                </tr>
              ) : null}
              {(data?.topRoutes ?? []).map((route) => (
                <tr key={`${route.origin}-${route.destination}`}>
                  <th scope="row" className="px-4 py-3 text-left font-medium">
                    {route.origin} → {route.destination}
                  </th>
                  <td className="tnum px-4 py-3 text-right">{route.trips}</td>
                  <td className="tnum px-4 py-3 text-right font-semibold">{formatFcfa(route.gmv)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  )
}

/* ------------------------------------------------------------- Files d'attente */

interface QueueTile {
  to: string
  icon: LucideIcon
  label: string
  value: number
  /** Ligne d'appui : montant du, anciennete du plus vieux dossier. */
  detail: string | null
  /** Question a laquelle le chiffre repond ; affichee au survol et aux lecteurs d'ecran. */
  question: string
}

/**
 * Ce qui attend une decision aujourd'hui, avant les courbes : chaque tuile est
 * un lien vers la file concernee. Rafraichi chaque minute. Un zero est une bonne
 * nouvelle et se lit comme tel (teinte neutre).
 */
function QueuesBlock() {
  const overview = useAdminOverview()
  const data = overview.data

  if (overview.isError) {
    return (
      <Card className="mb-5 px-4 py-3 text-label text-muted">
        Files d'attente indisponibles.{' '}
        <button
          type="button"
          className="font-semibold text-primary-ink underline-offset-4 hover:underline"
          onClick={() => overview.refetch()}
        >
          Réessayer
        </button>
      </Card>
    )
  }

  const tiles: QueueTile[] = data
    ? [
        {
          to: '/admin/reports',
          icon: AlertTriangle,
          label: 'Signalements ouverts',
          value: data.openReports,
          detail: data.inReviewReports > 0 ? `${data.inReviewReports.toLocaleString('fr-FR')} en cours d'instruction` : null,
          question: 'Qui attend une réponse de la modération ?',
        },
        {
          to: '/admin/verifications',
          icon: BadgeCheck,
          label: "Vérifications d'identité",
          value: data.pendingVerifications,
          detail: data.oldestPendingVerificationAt
            ? `Le plus ancien déposé ${formatFromNow(data.oldestPendingVerificationAt)}`
            : null,
          question: 'Depuis combien de temps un conducteur attend-il son badge ?',
        },
        {
          to: '/admin/payouts',
          icon: Wallet,
          label: 'Reversements dus',
          value: data.pendingPayouts,
          detail: data.pendingPayouts > 0 ? `${formatFcfa(data.pendingPayoutsAmountFcfa)} à virer aux conducteurs` : null,
          question: "Combien la plateforme doit-elle aux conducteurs aujourd'hui ?",
        },
        {
          to: '/admin/payments',
          icon: Banknote,
          label: 'Remboursements à traiter',
          value: data.refundsToHandle,
          detail: data.refundsToHandle > 0 ? "L'automate n'a pas pu finir : action manuelle" : null,
          question: 'Quels passagers attendent leur argent ?',
        },
      ]
    : []

  return (
    <section aria-labelledby="admin-queues" className="mb-5">
      <div className="mb-2 flex items-baseline justify-between gap-3">
        <h2 id="admin-queues" className="font-display text-base font-bold">
          Files d'attente
        </h2>
        <span className="text-caption text-muted">Rafraîchi chaque minute</span>
      </div>
      {overview.isPending || !data ? (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <StatSkeleton key={i} />
          ))}
        </div>
      ) : (
        <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {tiles.map((tile) => {
            const active = tile.value > 0
            return (
              <li key={tile.to}>
                <Link
                  to={tile.to}
                  title={tile.question}
                  aria-label={`${tile.label} : ${tile.value.toLocaleString('fr-FR')}${tile.detail ? `, ${tile.detail}` : ''}. ${tile.question}`}
                  className="block h-full rounded-[var(--radius-card)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                >
                  <Card
                    interactive
                    className={cn('flex h-full items-start gap-3 p-4', active && 'border-l-[3px] border-l-danger')}
                  >
                    <span
                      className={cn(
                        'flex size-10 shrink-0 items-center justify-center rounded-[var(--radius-control)]',
                        active ? 'bg-danger-soft text-danger-ink' : 'bg-surface-2 text-muted',
                      )}
                    >
                      <tile.icon className="size-5" aria-hidden />
                    </span>
                    <span className="min-w-0">
                      <span className="block text-label font-medium text-muted">{tile.label}</span>
                      <span className="tnum mt-1 block font-display text-display font-extrabold leading-none tracking-[-0.03em] text-ink">
                        {tile.value.toLocaleString('fr-FR')}
                      </span>
                      <span className="mt-1.5 block text-caption text-muted">{tile.detail ?? 'Rien en attente'}</span>
                    </span>
                    <ArrowRight className="ml-auto mt-1 size-4 shrink-0 text-muted" aria-hidden />
                  </Card>
                </Link>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
