import { m } from 'motion/react'
import { CalendarRange, Download, Smartphone } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Progress } from '@/components/ui/misc'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { ErrorState, StatSkeleton } from '@/components/ui/states'
import { AdminPageHeader } from '@/components/layout/AdminPageHeader'
import { PageMeta } from '@/components/layout/PageMeta'
import { downloadRetentionCsv, useAdminRetention } from '@/hooks/useAdmin'
import { describeError } from '@/lib/errors'
import { formatFcfa } from '@/lib/format'
import { listContainer } from '@/lib/motion'
import { providerLabel } from '@/lib/payments'
import type { PaymentMethod } from '@/api/types'
import type { PaymentProvider } from '@/api/extended'
import { formatPercent, formatRatio, ratioDelta, relativeDelta } from './adminMetrics'
import { StatTile, TableHead } from './AdminWidgets'

const METHOD_LABEL: Record<PaymentMethod, string> = {
  MOMO_DEPOSIT: 'Acompte mobile money',
  MOMO_FULL: 'Mobile money intégral',
  CASH: 'Espèces à bord',
}

const PROVIDERS: PaymentProvider[] = ['MTN_MOMO', 'MOOV_MONEY', 'CELTIIS_CASH']

function isProvider(value: string): value is PaymentProvider {
  return (PROVIDERS as string[]).includes(value)
}

/**
 * Retention, paiement et panier (CLAUDE.md, sections 2 et 3 des KPI). Chaque
 * bloc repond a une question du fondateur et pointe une decision : relancer
 * les conducteurs qui ne republient pas, pousser le quotidien, appeler un
 * operateur en panne, revoir la part du mode especes qui echappe a la commission.
 * Les taux sont calcules cote serveur (SQL agrege, fractions 0..1) ; ici on ne
 * fait que les afficher avec leur variation.
 */
export function AdminRetention() {
  const [days, setDays] = useState(30)
  const [exporting, setExporting] = useState(false)
  const retention = useAdminRetention(days)

  if (retention.isError) {
    return <ErrorState description={describeError(retention.error)} onRetry={() => retention.refetch()} />
  }

  const data = retention.data
  const prev = data?.previous

  const exportCsv = async () => {
    setExporting(true)
    try {
      await downloadRetentionCsv(days)
    } catch (error) {
      toast.error(describeError(error, "Export impossible pour l'instant."))
    } finally {
      setExporting(false)
    }
  }

  const totalMethodCount = data?.paymentMethodShare.reduce((sum, row) => sum + row.count, 0) ?? 0
  const totalMethodAmount = data?.paymentMethodShare.reduce((sum, row) => sum + row.amountFcfa, 0) ?? 0
  const cashRow = data?.paymentMethodShare.find((row) => row.method === 'CASH')
  const cashShare = totalMethodAmount > 0 && cashRow ? (cashRow.amountFcfa / totalMethodAmount) * 100 : 0
  const totalAttempts = data?.kkiapayFailureByOperator.reduce((sum, row) => sum + row.attempts, 0) ?? 0
  const totalFailures = data?.kkiapayFailureByOperator.reduce((sum, row) => sum + row.failures, 0) ?? 0

  return (
    <div>
      <PageMeta title="Rétention · Back-office" noindex />
      <AdminPageHeader
        title="Rétention et paiement"
        description="Le volume ne dit pas si l'affaire tourne : ces chiffres, oui. Chaque bloc répond à une question et appelle une décision."
        actions={
          <>
            <Select value={String(days)} onValueChange={(value) => setDays(Number(value))}>
              <SelectTrigger className="h-10 w-auto min-w-[150px] gap-2 text-body" aria-label="Période analysée">
                <CalendarRange className="size-4 text-muted" aria-hidden />
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="30">30 derniers jours</SelectItem>
                <SelectItem value="60">60 derniers jours</SelectItem>
                <SelectItem value="90">90 derniers jours</SelectItem>
              </SelectContent>
            </Select>
            <Button variant="secondary" size="sm" className="h-10" onClick={exportCsv} loading={exporting} disabled={!data}>
              <Download className="size-4" aria-hidden />
              Exporter CSV
            </Button>
          </>
        }
      />

      {/* --- Retention : ce qui distingue un produit d'un depannage --- */}
      <h3 className="mb-2 font-display text-base font-bold">Les conducteurs et les passagers reviennent-ils ?</h3>
      {retention.isPending || !data || !prev ? (
        <div className="grid gap-3 sm:grid-cols-3">
          {[0, 1, 2].map((i) => (
            <StatSkeleton key={i} />
          ))}
        </div>
      ) : (
        <m.div variants={listContainer} initial="hidden" animate="show" className="grid gap-3 sm:grid-cols-3">
          <StatTile
            label="Conducteurs qui republient à S+1"
            value={formatRatio(data.driverRetentionW1)}
            delta={ratioDelta(data.driverRetentionW1, prev.driverRetentionW1)}
            deltaUnit="pts"
            hint="Part des conducteurs ayant publié la semaine suivant leur première publication"
          />
          <StatTile
            label="Conducteurs qui republient à S+4"
            value={formatRatio(data.driverRetentionW4)}
            delta={ratioDelta(data.driverRetentionW4, prev.driverRetentionW4)}
            deltaUnit="pts"
            hint="Quatre semaines après : la vraie fidélité, pas l'essai"
          />
          <StatTile
            label="Passagers qui réservent à nouveau"
            value={formatRatio(data.passengerRetention30d)}
            delta={ratioDelta(data.passengerRetention30d, prev.passengerRetention30d)}
            deltaUnit="pts"
            hint="Seconde réservation sous 30 jours"
          />
        </m.div>
      )}

      {/* --- La these produit : le quotidien --- */}
      <Card className="mt-4 p-4">
        <h3 className="font-display text-base font-bold">Le quotidien décolle-t-il ?</h3>
        <p className="text-label text-muted">
          Si la navette domicile-travail ne prend pas, le modèle économique ne tient pas, quel que soit le volume
          interurbain.
        </p>
        {retention.isPending || !data || !prev ? (
          <div className="shimmer mt-3 h-24 rounded-[var(--radius-control)]" />
        ) : (
          <div className="mt-3 grid gap-4 sm:grid-cols-3">
            <div>
              <p className="text-label text-muted">Part du quotidien dans les réservations</p>
              <p className="tnum mt-1 font-display text-display font-extrabold leading-none tracking-[-0.03em]">
                {formatRatio(data.dailyModeShare)}
              </p>
              <Progress
                value={Math.min(100, (data.dailyModeShare ?? 0) * 100)}
                tone={(data.dailyModeShare ?? 0) >= 0.4 ? 'success' : (data.dailyModeShare ?? 0) >= 0.2 ? 'accent' : 'danger'}
                className="mt-2"
                aria-label="Part du mode quotidien"
              />
              <p className="mt-1 text-caption text-muted">
                Période précédente : {formatRatio(prev.dailyModeShare)}
              </p>
            </div>
            <div>
              <p className="text-label text-muted">Navettes actives</p>
              <p className="tnum mt-1 font-display text-display font-extrabold leading-none tracking-[-0.03em]">
                {data.activeRecurringTemplates.toLocaleString('fr-FR')}
              </p>
              <p className="mt-1 text-caption text-muted">
                Modèles récurrents avec au moins un départ à venir · période précédente :{' '}
                {prev.activeRecurringTemplates.toLocaleString('fr-FR')} (
                {relativeDelta(data.activeRecurringTemplates, prev.activeRecurringTemplates).toFixed(1).replace('.', ',')} %)
              </p>
            </div>
            <div>
              <p className="text-label text-muted">Places remplies par départ de navette</p>
              <p className="tnum mt-1 font-display text-display font-extrabold leading-none tracking-[-0.03em]">
                {data.avgFilledSeatsPerOccurrence == null ? '—' : data.avgFilledSeatsPerOccurrence.toFixed(1).replace('.', ',')}
              </p>
              <p className="mt-1 text-caption text-muted">
                En moyenne, sur les occurrences parties · période précédente :{' '}
                {prev.avgFilledSeatsPerOccurrence == null ? '—' : prev.avgFilledSeatsPerOccurrence.toFixed(1).replace('.', ',')}
              </p>
            </div>
          </div>
        )}
      </Card>

      {/* --- Paiement --- */}
      <h3 className="mb-2 mt-6 font-display text-base font-bold">L'acompte est-il encaissé ?</h3>
      {retention.isPending || !data || !prev ? (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <StatSkeleton key={i} />
          ))}
        </div>
      ) : (
        <m.div variants={listContainer} initial="hidden" animate="show" className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          <StatTile
            label="Réservation → acompte encaissé"
            value={formatRatio(data.bookingToDepositRate)}
            delta={ratioDelta(data.bookingToDepositRate, prev.bookingToDepositRate)}
            deltaUnit="pts"
            hint="Réservations en mobile money dont l'acompte a été reçu"
          />
          <StatTile
            label="Expirées faute d'acompte"
            value={formatRatio(data.expiredBookingShare)}
            delta={ratioDelta(data.expiredBookingShare, prev.expiredBookingShare)}
            deltaUnit="pts"
            lowerIsBetter
            hint="Places relibérées après 20 minutes sans paiement"
          />
          <StatTile
            label="Panier moyen"
            value={data.averageBasketFcfa == null ? '—' : formatFcfa(Math.round(data.averageBasketFcfa))}
            delta={
              data.averageBasketFcfa == null || prev.averageBasketFcfa == null
                ? null
                : relativeDelta(data.averageBasketFcfa, prev.averageBasketFcfa)
            }
            hint="Montant moyen d'une réservation confirmée"
          />
          <StatTile
            label="Places par réservation"
            value={data.seatsPerBooking == null ? '—' : data.seatsPerBooking.toFixed(2).replace('.', ',')}
            delta={data.seatsPerBooking == null || prev.seatsPerBooking == null ? null : relativeDelta(data.seatsPerBooking, prev.seatsPerBooking)}
            hint="Un panier qui grossit sans plus de réservations, c'est des groupes"
          />
        </m.div>
      )}

      <div className="mt-4 grid gap-4 xl:grid-cols-2">
        {/* --- Echecs Kkiapay par operateur --- */}
        <Card>
          <div className="px-4 pt-4">
            <h3 className="font-display text-base font-bold">Un opérateur est-il en panne ?</h3>
            <p className="text-label text-muted">
              Échecs Kkiapay par opérateur sur la période. Un taux qui grimpe sur un seul opérateur se voit ici en
              quelques minutes, pas par les plaintes.
            </p>
          </div>
          {retention.isPending || !data ? (
            <div className="shimmer m-4 h-32 rounded-[var(--radius-control)]" />
          ) : data.kkiapayFailureByOperator.length === 0 ? (
            <p className="px-4 py-8 text-center text-body text-muted">Aucune tentative de paiement sur la période.</p>
          ) : (
            <div className="mt-3 overflow-x-auto">
              <table className="w-full min-w-[420px] text-body">
                <TableHead>
                  <th scope="col" className="px-4 py-2 font-semibold">Opérateur</th>
                  <th scope="col" className="px-4 py-2 text-right font-semibold">Tentatives</th>
                  <th scope="col" className="px-4 py-2 text-right font-semibold">Échecs</th>
                  <th scope="col" className="px-4 py-2 text-right font-semibold">Taux d'échec</th>
                </TableHead>
                <tbody className="divide-y divide-rule">
                  {data.kkiapayFailureByOperator.map((row) => {
                    const rate = row.attempts === 0 ? 0 : (row.failures / row.attempts) * 100
                    return (
                      <tr key={row.operator}>
                        <th scope="row" className="px-4 py-3 text-left font-medium">
                          <span className="inline-flex items-center gap-2">
                            <Smartphone className="size-4 text-muted" aria-hidden />
                            {isProvider(row.operator) ? providerLabel(row.operator) : row.operator}
                          </span>
                        </th>
                        <td className="tnum px-4 py-3 text-right">{row.attempts.toLocaleString('fr-FR')}</td>
                        <td className="tnum px-4 py-3 text-right font-semibold text-danger-ink">
                          {row.failures.toLocaleString('fr-FR')}
                        </td>
                        <td className="px-4 py-3 text-right">
                          <Badge tone={rate >= 25 ? 'danger' : rate >= 10 ? 'warning' : 'success'}>{formatPercent(rate, 0)}</Badge>
                        </td>
                      </tr>
                    )
                  })}
                  <tr className="bg-surface-2">
                    <th scope="row" className="px-4 py-2.5 text-left text-label font-semibold text-muted">
                      Ensemble
                    </th>
                    <td className="tnum px-4 py-2.5 text-right text-label">{totalAttempts.toLocaleString('fr-FR')}</td>
                    <td className="tnum px-4 py-2.5 text-right text-label">{totalFailures.toLocaleString('fr-FR')}</td>
                    <td className="tnum px-4 py-2.5 text-right text-label">
                      {formatPercent(totalAttempts === 0 ? 0 : (totalFailures / totalAttempts) * 100, 0)}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          )}
        </Card>

        {/* --- Repartition des modes de paiement --- */}
        <Card>
          <div className="px-4 pt-4">
            <h3 className="font-display text-base font-bold">Quelle part échappe à la commission ?</h3>
            <p className="text-label text-muted">
              Le mode espèces confirme sans acompte : Ekuiseo n'y prélève rien. Sa part est à surveiller, pas à
              interdire.
            </p>
          </div>
          {retention.isPending || !data ? (
            <div className="shimmer m-4 h-32 rounded-[var(--radius-control)]" />
          ) : data.paymentMethodShare.length === 0 ? (
            <p className="px-4 py-8 text-center text-body text-muted">Aucune réservation sur la période.</p>
          ) : (
            <>
              <ul className="mt-3 divide-y divide-rule">
                {data.paymentMethodShare.map((row) => {
                  const share = totalMethodCount === 0 ? 0 : (row.count / totalMethodCount) * 100
                  return (
                    <li key={row.method} className="px-4 py-3">
                      <div className="flex items-baseline justify-between gap-3">
                        <span className="font-medium">{METHOD_LABEL[row.method] ?? row.method}</span>
                        <span className="tnum font-display text-title font-extrabold">{formatPercent(share)}</span>
                      </div>
                      <Progress
                        value={Math.min(100, share)}
                        tone={row.method === 'CASH' ? 'accent' : 'primary'}
                        className="mt-2"
                        aria-label={`Part du mode ${METHOD_LABEL[row.method] ?? row.method}`}
                      />
                      <p className="tnum mt-1.5 text-caption text-muted">
                        {row.count.toLocaleString('fr-FR')} réservation{row.count > 1 ? 's' : ''} · {formatFcfa(row.amountFcfa)}
                      </p>
                    </li>
                  )
                })}
              </ul>
              <p className="border-t border-rule px-4 py-3 text-label text-ink-2">
                <span className="font-semibold">{formatPercent(cashShare, 0)} du volume</span> en espèces, sans commission
                {cashRow ? ` (${formatFcfa(cashRow.amountFcfa)})` : ''}.
              </p>
            </>
          )}
        </Card>
      </div>
    </div>
  )
}
