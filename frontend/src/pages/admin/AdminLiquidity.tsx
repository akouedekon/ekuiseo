import { motion } from 'motion/react'
import { CalendarRange, Download, SearchX } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Progress } from '@/components/ui/misc'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { EmptyState, ErrorState, StatSkeleton } from '@/components/ui/states'
import { SectionTitle } from '@/components/layout/PageContainer'
import { downloadLiquidityCsv, useAdminLiquidity } from '@/hooks/useAdmin'
import { formatFromNow } from '@/lib/format'
import { listContainer } from '@/lib/motion'
import type { TripType } from '@/api/types'
import { formatHours, formatPercent, pointsDelta, relativeDelta } from './adminMetrics'
import { StatTile, TableHead } from './AdminWidgets'

const MODE_LABEL: Record<TripType, string> = {
  INTERURBAIN: 'Interurbain',
  QUOTIDIEN: 'Quotidien',
}

/**
 * Liquidite du marche : les passagers trouvent-ils, les conducteurs
 * remplissent-ils ? Chaque bloc repond a une question et pointe une action -
 * un chiffre qui n'appelle aucune decision n'a pas sa place ici.
 */
export function AdminLiquidity() {
  const [days, setDays] = useState(30)
  const [exporting, setExporting] = useState(false)
  const liquidity = useAdminLiquidity(days)

  if (liquidity.isError) return <ErrorState onRetry={() => liquidity.refetch()} />

  const data = liquidity.data?.data
  const cur = data?.current
  const prev = data?.previous

  const exportCsv = async () => {
    setExporting(true)
    try {
      await downloadLiquidityCsv(days)
    } catch {
      toast.error("Export impossible pour l'instant. Vérifiez votre connexion, puis réessayez.")
    } finally {
      setExporting(false)
    }
  }

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div>
          <SectionTitle className="mb-0">Liquidité</SectionTitle>
          <p className="mt-0.5 text-[13px] text-muted">
            Un passager qui ne trouve rien ne revient pas ; un conducteur sans passager ne republie pas.
          </p>
        </div>
        <div className="flex items-center gap-2">
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
          <Button variant="secondary" size="sm" className="h-10" onClick={exportCsv} loading={exporting} disabled={!data}>
            <Download className="size-4" aria-hidden />
            Exporter CSV
          </Button>
        </div>
      </div>

      {/* --- Cote demande : les passagers trouvent-ils ? --- */}
      <h2 className="mb-2 font-display text-[15px] font-bold">Les passagers trouvent-ils ?</h2>
      {liquidity.isPending || !cur || !prev ? (
        <div className="grid gap-3 sm:grid-cols-3">
          {[0, 1, 2].map((i) => (
            <StatSkeleton key={i} />
          ))}
        </div>
      ) : (
        <motion.div variants={listContainer} initial="hidden" animate="show" className="grid gap-3 sm:grid-cols-3">
          <StatTile
            label="Recherches"
            value={cur.searches.toLocaleString('fr-FR')}
            delta={relativeDelta(cur.searches, prev.searches)}
          />
          <StatTile
            label="Recherches abouties"
            value={formatPercent(cur.searchSuccessRate)}
            delta={pointsDelta(cur.searchSuccessRate, prev.searchSuccessRate)}
            deltaUnit="pts"
            hint={`${cur.searchesWithResults.toLocaleString('fr-FR')} recherches avec au moins un trajet`}
          />
          <StatTile
            label="Recherche → réservation"
            value={formatPercent(cur.searchToBookingRate)}
            delta={pointsDelta(cur.searchToBookingRate, prev.searchToBookingRate)}
            deltaUnit="pts"
            hint={`Utilisateurs connectés, réservation sous 24 h (${cur.searchesByUsers.toLocaleString('fr-FR')} recherches attribuables)`}
          />
        </motion.div>
      )}

      {/* --- Axes en penurie : la liste a demarcher --- */}
      <Card className="mt-4">
        <div className="px-4 pt-4">
          <h2 className="font-display text-[15px] font-bold">Axes en pénurie</h2>
          <p className="text-[13px] text-muted">
            Couples origine → destination recherchés sans résultat. C'est la liste des corridors à démarcher en
            priorité auprès des conducteurs.
          </p>
        </div>
        {liquidity.isPending || !data ? (
          <div className="shimmer m-4 h-40 rounded-[var(--radius-control)]" />
        ) : data.shortageRoutes.length === 0 ? (
          <EmptyState
            icon={SearchX}
            title="Aucune recherche infructueuse"
            description="Sur la période, chaque recherche a renvoyé au moins un trajet."
          />
        ) : (
          <div className="mt-3 overflow-x-auto">
            <table className="w-full min-w-[560px] text-[14px]">
              <TableHead>
                <th scope="col" className="px-4 py-2 font-semibold">Axe</th>
                <th scope="col" className="px-4 py-2 text-right font-semibold">Recherches</th>
                <th scope="col" className="px-4 py-2 text-right font-semibold">Sans résultat</th>
                <th scope="col" className="px-4 py-2 text-right font-semibold">Part</th>
                <th scope="col" className="px-4 py-2 text-right font-semibold">Dernière recherche</th>
              </TableHead>
              <tbody className="divide-y divide-rule">
                {data.shortageRoutes.map((route) => {
                  const share = route.searches === 0 ? 0 : (route.searchesWithoutResults / route.searches) * 100
                  return (
                    <tr key={`${route.origin}-${route.destination}`}>
                      <th scope="row" className="px-4 py-3 text-left font-medium">
                        {route.origin} → {route.destination}
                      </th>
                      <td className="tnum px-4 py-3 text-right">{route.searches.toLocaleString('fr-FR')}</td>
                      <td className="tnum px-4 py-3 text-right font-semibold text-[var(--vermillon)]">
                        {route.searchesWithoutResults.toLocaleString('fr-FR')}
                      </td>
                      <td className="px-4 py-3 text-right">
                        <Badge tone={share >= 75 ? 'danger' : share >= 40 ? 'warning' : 'neutral'}>
                          {formatPercent(share, 0)}
                        </Badge>
                      </td>
                      <td className="px-4 py-3 text-right text-muted">
                        {route.lastSearchedAt ? formatFromNow(route.lastSearchedAt) : '—'}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* --- Cote offre : les conducteurs remplissent-ils ? --- */}
      <h2 className="mb-2 mt-6 font-display text-[15px] font-bold">Les conducteurs remplissent-ils ?</h2>
      {liquidity.isPending || !cur || !prev ? (
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
            label="Taux de remplissage"
            value={formatPercent(cur.fillRate)}
            delta={pointsDelta(cur.fillRate, prev.fillRate)}
            deltaUnit="pts"
            hint={`${cur.seatsBooked.toLocaleString('fr-FR')} places vendues sur ${cur.seatsPublished.toLocaleString('fr-FR')} publiées`}
          />
          <StatTile
            label="Trajets orphelins"
            value={formatPercent(cur.orphanRate)}
            delta={pointsDelta(cur.orphanRate, prev.orphanRate)}
            deltaUnit="pts"
            lowerIsBetter
            hint={`${cur.orphanTrips.toLocaleString('fr-FR')} trajets partis sans aucune réservation`}
          />
          <StatTile
            label="Délai avant 1re réservation"
            value={cur.medianHoursToFirstBooking === null ? '—' : formatHours(cur.medianHoursToFirstBooking)}
            delta={
              cur.medianHoursToFirstBooking === null || prev.medianHoursToFirstBooking === null
                ? null
                : Math.round((cur.medianHoursToFirstBooking - prev.medianHoursToFirstBooking) * 10) / 10
            }
            deltaUnit="h"
            lowerIsBetter
            hint={`Médiane sur ${cur.firstBookingSampleSize.toLocaleString('fr-FR')} trajets réservés`}
          />
          <StatTile
            label="Trajets partis"
            value={cur.trips.toLocaleString('fr-FR')}
            delta={relativeDelta(cur.trips, prev.trips)}
            hint="Hors brouillons et annulés"
          />
        </motion.div>
      )}

      <div className="mt-4 grid gap-4 xl:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        {/* --- Par mode : la these produit --- */}
        <Card>
          <div className="px-4 pt-4">
            <h2 className="font-display text-[15px] font-bold">Remplissage par mode</h2>
            <p className="text-[13px] text-muted">
              Le quotidien est le modèle économique : s'il ne se remplit pas, le volume interurbain ne suffira pas.
            </p>
          </div>
          {liquidity.isPending || !data ? (
            <div className="shimmer m-4 h-32 rounded-[var(--radius-control)]" />
          ) : data.fillByMode.length === 0 ? (
            <p className="px-4 py-8 text-center text-[14px] text-muted">Aucun trajet parti sur la période.</p>
          ) : (
            <ul className="mt-3 divide-y divide-rule">
              {data.fillByMode.map((mode) => (
                <li key={mode.tripType} className="px-4 py-3">
                  <div className="flex items-baseline justify-between gap-3">
                    <span className="font-medium">{MODE_LABEL[mode.tripType]}</span>
                    <span className="tnum font-display text-[18px] font-extrabold">{formatPercent(mode.fillRate)}</span>
                  </div>
                  <Progress
                    value={Math.min(100, mode.fillRate)}
                    tone={mode.fillRate >= 60 ? 'vert' : mode.fillRate >= 40 ? 'ocre' : 'vermillon'}
                    className="mt-2"
                    aria-label={`Taux de remplissage ${MODE_LABEL[mode.tripType]}`}
                  />
                  <p className="tnum mt-1.5 text-[12px] text-muted">
                    {mode.seatsBooked.toLocaleString('fr-FR')} / {mode.seatsPublished.toLocaleString('fr-FR')} places ·{' '}
                    {mode.trips.toLocaleString('fr-FR')} trajets · {formatPercent(mode.orphanRate, 0)} orphelins
                  </p>
                </li>
              ))}
            </ul>
          )}
        </Card>

        {/* --- Par axe --- */}
        <Card>
          <div className="px-4 pt-4">
            <h2 className="font-display text-[15px] font-bold">Remplissage par axe</h2>
            <p className="text-[13px] text-muted">Classés par places publiées</p>
          </div>
          {liquidity.isPending || !data ? (
            <div className="shimmer m-4 h-32 rounded-[var(--radius-control)]" />
          ) : (
            <div className="mt-3 overflow-x-auto">
              <table className="w-full min-w-[520px] text-[14px]">
                <TableHead>
                  <th scope="col" className="px-4 py-2 font-semibold">Axe</th>
                  <th scope="col" className="px-4 py-2 text-right font-semibold">Trajets</th>
                  <th scope="col" className="px-4 py-2 text-right font-semibold">Places</th>
                  <th scope="col" className="px-4 py-2 text-right font-semibold">Remplissage</th>
                  <th scope="col" className="px-4 py-2 text-right font-semibold">Orphelins</th>
                </TableHead>
                <tbody className="divide-y divide-rule">
                  {data.fillByRoute.map((route) => (
                    <tr key={`${route.origin}-${route.destination}-${route.tripType}`}>
                      <th scope="row" className="px-4 py-3 text-left font-medium">
                        <span className="block">
                          {route.origin} → {route.destination}
                        </span>
                        <span className="text-[12px] font-normal text-muted">{MODE_LABEL[route.tripType]}</span>
                      </th>
                      <td className="tnum px-4 py-3 text-right">{route.trips.toLocaleString('fr-FR')}</td>
                      <td className="tnum px-4 py-3 text-right text-muted">
                        {route.seatsBooked.toLocaleString('fr-FR')} / {route.seatsPublished.toLocaleString('fr-FR')}
                      </td>
                      <td className="tnum px-4 py-3 text-right font-semibold">{formatPercent(route.fillRate)}</td>
                      <td className="tnum px-4 py-3 text-right">{route.orphanTrips.toLocaleString('fr-FR')}</td>
                    </tr>
                  ))}
                  {data.fillByRoute.length === 0 ? (
                    <tr>
                      <td colSpan={5} className="px-4 py-8 text-center text-muted">
                        Aucun trajet parti sur la période.
                      </td>
                    </tr>
                  ) : null}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>
    </div>
  )
}
