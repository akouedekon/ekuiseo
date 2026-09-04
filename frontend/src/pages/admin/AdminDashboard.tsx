import { motion } from 'motion/react'
import { ArrowRight, CalendarRange, Target } from 'lucide-react'
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
import { SectionTitle } from '@/components/layout/PageContainer'
import { useAdminLiquidity, useAdminStats } from '@/hooks/useAdmin'
import { formatFcfa, formatFcfaCompact } from '@/lib/format'
import { listContainer } from '@/lib/motion'
import type { BookingStatus } from '@/api/types'
import { CHART, formatHours, formatPercent, pointsDelta, relativeDelta } from './adminMetrics'
import { ChartTooltip, DeltaBadge, StatTile } from './AdminWidgets'

const STATUS_LABEL: Record<BookingStatus, string> = {
  CONFIRMED: 'Confirmées',
  PENDING_PAYMENT: 'Acompte attendu',
  COMPLETED: 'Terminées',
  CANCELLED_BY_PASSENGER: 'Annul. passager',
  CANCELLED_BY_DRIVER: 'Annul. conducteur',
  NO_SHOW: 'Non présentés',
}

const STATUS_COLOR: Record<BookingStatus, string> = {
  CONFIRMED: CHART.vert,
  PENDING_PAYMENT: CHART.ocre,
  COMPLETED: CHART.indigo,
  CANCELLED_BY_PASSENGER: CHART.vermillon,
  CANCELLED_BY_DRIVER: CHART.vermillon,
  NO_SHOW: CHART.muted,
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

  if (stats.isError) return <ErrorState onRetry={() => stats.refetch()} />

  const data = stats.data?.data
  const liq = liquidity.data?.data

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

      <div className="mb-4 flex items-center justify-between gap-3">
        <SectionTitle className="mb-0">Vue d'ensemble</SectionTitle>
        <Select value={String(days)} onValueChange={(value) => setDays(Number(value))}>
          <SelectTrigger className="h-10 w-auto min-w-[150px] gap-2 text-[14px]" aria-label="Période analysée">
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

      {/* --- Metrique nord : places confirmees vs seuil de viabilite --- */}
      <Card className="p-4">
        {liquidity.isPending || !liq ? (
          <div className="shimmer h-[168px] rounded-[var(--radius-control)]" />
        ) : liquidity.isError ? (
          <p className="text-[14px] text-muted">Métrique nord indisponible pour l'instant.</p>
        ) : (
          <div className="grid gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
            <div>
              <p className="flex items-center gap-1.5 text-[13px] text-muted">
                <Target className="size-4" aria-hidden />
                Places confirmées sur la période
              </p>
              <p className="tnum mt-1.5 font-display text-[36px] font-extrabold leading-none tracking-[-0.03em]">
                {liq.northStar.confirmedSeats.toLocaleString('fr-FR')}
              </p>
              <p className="mt-2 flex flex-wrap items-center gap-x-1.5 text-[13px]">
                <DeltaBadge
                  delta={relativeDelta(liq.northStar.confirmedSeats, liq.northStar.previousConfirmedSeats)}
                  unit="%"
                />
                <span className="text-muted">vs période précédente</span>
              </p>
              <div className="mt-4">
                <div className="flex items-baseline justify-between gap-3 text-[13px]">
                  <span className="text-muted">Rythme mensuel</span>
                  <span className="tnum font-semibold">
                    {Math.round(liq.northStar.monthlyPace).toLocaleString('fr-FR')} /{' '}
                    {liq.northStar.monthlyTarget.toLocaleString('fr-FR')} places
                  </span>
                </div>
                <Progress
                  value={Math.min(100, liq.northStar.progressPercent)}
                  tone={liq.northStar.progressPercent >= 100 ? 'vert' : liq.northStar.progressPercent >= 50 ? 'ocre' : 'vermillon'}
                  className="mt-1.5"
                  aria-label="Progression vers le seuil de viabilité"
                />
                <p className="mt-1.5 text-[12px] text-muted">
                  {formatPercent(liq.northStar.progressPercent, 0)} du seuil de viabilité (2 000 places par mois). En
                  dessous, le projet paie l'hébergement, pas un salaire.
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
                  <RechartsTooltip content={<ChartTooltip />} cursor={{ fill: 'var(--surface-calm)' }} />
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
        <h2 className="font-display text-[15px] font-bold">Liquidité</h2>
        <Link
          to="/admin/liquidity"
          className="inline-flex items-center gap-1 text-[13px] font-semibold text-[var(--indigo)] hover:underline"
        >
          Axes en pénurie et remplissage
          <ArrowRight className="size-3.5" aria-hidden />
        </Link>
      </div>
      {liquidity.isPending || !liq ? (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <StatSkeleton key={i} />
          ))}
        </div>
      ) : (
        <motion.div
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
              liq.current.medianHoursToFirstBooking === null ? '—' : formatHours(liq.current.medianHoursToFirstBooking)
            }
            delta={
              liq.current.medianHoursToFirstBooking === null || liq.previous.medianHoursToFirstBooking === null
                ? null
                : Math.round((liq.current.medianHoursToFirstBooking - liq.previous.medianHoursToFirstBooking) * 10) / 10
            }
            deltaUnit="h"
            lowerIsBetter
            hint="Médiane, publication → première réservation"
          />
        </motion.div>
      )}

      {/* --- Volume --- */}
      <h2 className="mb-2 mt-5 font-display text-[15px] font-bold">Volume</h2>
      {stats.isPending || !data ? (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <StatSkeleton key={i} />
          ))}
        </div>
      ) : (
        <motion.div
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
        </motion.div>
      )}

      {/* --- Volume : trajets et reservations --- */}
      <Card className="mt-4 p-4">
        <h2 className="font-display text-[15px] font-bold">Activité quotidienne</h2>
        <p className="mb-3 text-[13px] text-muted">Trajets publiés et réservations créées, par jour</p>
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
          <h2 className="font-display text-[15px] font-bold">Volume et revenus</h2>
          <p className="mb-3 text-[13px] text-muted">En FCFA, par jour</p>
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
          <h2 className="font-display text-[15px] font-bold">Réservations par statut</h2>
          <p className="mb-3 text-[13px] text-muted">Sur la période</p>
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
                  <RechartsTooltip content={<ChartTooltip />} cursor={{ fill: 'var(--surface-calm)' }} />
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
          <h2 className="font-display text-[15px] font-bold">Axes les plus actifs</h2>
          <p className="text-[13px] text-muted">Classés par volume d'affaires</p>
        </div>
        <div className="mt-3 overflow-x-auto">
          <table className="w-full min-w-[420px] text-[14px]">
            <thead>
              <tr className="border-y border-rule bg-[var(--surface-calm)] text-left text-[12px] uppercase tracking-wide text-muted">
                <th scope="col" className="px-4 py-2 font-semibold">Axe</th>
                <th scope="col" className="px-4 py-2 text-right font-semibold">Trajets</th>
                <th scope="col" className="px-4 py-2 text-right font-semibold">Volume</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-rule">
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
