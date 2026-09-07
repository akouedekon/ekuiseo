import { Banknote, CircleX, PlayCircle, RotateCcw, Wallet } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { AdminPageHeader } from '@/components/layout/AdminPageHeader'
import { DataTable, type DataTableColumn } from '@/components/tables/DataTable'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input, Textarea } from '@/components/ui/input'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { providerLabel } from '@/lib/payments'
import { AdminPagination } from '@/features/admin/AdminPagination'
import { PaymentAccountsToVerify } from '@/features/admin/PaymentAccountsToVerify'
import { useAdminOverview, useAdminPayouts, useFailPayout, useRunPayoutBatch, useSettlePayout } from '@/hooks/useAdmin'
import { describeError } from '@/lib/errors'
import { formatDayShort, formatFcfa, formatPhone } from '@/lib/format'
import type { AdminPayoutResponse, PayoutStatus } from '@/api/extended'

const STATUS: Record<PayoutStatus, { label: string; tone: 'warning' | 'indigo' | 'success' | 'danger'; order: number }> = {
  PENDING: { label: 'À verser', tone: 'warning', order: 0 },
  FAILED: { label: 'Échec', tone: 'danger', order: 1 },
  PROCESSING: { label: 'En cours', tone: 'indigo', order: 2 },
  PAID: { label: 'Versé', tone: 'success', order: 3 },
  SETTLED: { label: 'Versé', tone: 'success', order: 3 },
}

const ACCENT: Partial<Record<PayoutStatus, string>> = {
  PENDING: 'var(--accent)',
  FAILED: 'var(--danger)',
}

/** Lots qui attendent encore un virement : a verser, en cours, ou a relancer apres echec. */
function isDue(payout: AdminPayoutResponse): boolean {
  return payout.status === 'PENDING' || payout.status === 'PROCESSING' || payout.status === 'FAILED'
}

function isSettled(payout: AdminPayoutResponse): boolean {
  return payout.status === 'PAID' || payout.status === 'SETTLED'
}

function accountLabel(payout: AdminPayoutResponse): string {
  const provider = payout.provider ? providerLabel(payout.provider) : null
  const phone = payout.phone ? formatPhone(payout.phone) : null
  if (!provider && !phone) return 'Aucun compte mobile money enregistré'
  return [provider, phone].filter(Boolean).join(' ')
}

const COLUMNS: DataTableColumn<AdminPayoutResponse>[] = [
  {
    id: 'driver',
    header: 'Conducteur',
    mobile: 'title',
    sortValue: (payout) => payout.driverName,
    cell: (payout) => (
      <Link to={`/admin/users/${payout.driverId}`} className="font-semibold text-ink underline-offset-4 hover:underline">
        {payout.driverName}
      </Link>
    ),
  },
  {
    id: 'account',
    header: 'Compte',
    mobile: 'meta',
    className: 'hidden xl:table-cell',
    cell: (payout) => (
      <span className="tnum block text-label text-ink-2">
        {payout.provider ? <span className="block">{providerLabel(payout.provider)}</span> : null}
        <span className={payout.phone ? 'block whitespace-nowrap text-muted' : 'block text-danger-ink'}>
          {payout.phone ? formatPhone(payout.phone) : 'Aucun compte enregistré'}
        </span>
      </span>
    ),
  },
  {
    id: 'period',
    header: 'Période',
    mobile: 'meta',
    sortValue: (payout) => payout.periodStart,
    cell: (payout) => (
      <span className="tnum whitespace-nowrap text-label text-ink-2">
        {formatDayShort(payout.periodStart)} → {formatDayShort(payout.periodEnd)}
      </span>
    ),
  },
  {
    id: 'trips',
    header: 'Trajets',
    align: 'right',
    mobile: 'value',
    sortValue: (payout) => payout.tripCount,
    cell: (payout) => payout.tripCount.toLocaleString('fr-FR'),
  },
  {
    id: 'amount',
    header: 'Montant',
    align: 'right',
    mobile: 'value',
    sortValue: (payout) => payout.amount,
    cell: (payout) => (
      <span className="font-display font-bold text-ink">
        {formatFcfa(payout.amount)}
        {payout.reversedCount > 0 ? (
          <span className="block text-caption font-normal text-danger-ink">
            −{formatFcfa(payout.reversedAmount)} à déduire ({payout.reversedCount} remboursement
            {payout.reversedCount > 1 ? 's' : ''})
          </span>
        ) : null}
      </span>
    ),
  },
  {
    id: 'status',
    header: 'Statut',
    mobile: 'badge',
    sortValue: (payout) => STATUS[payout.status]?.order ?? 9,
    cell: (payout) => <Badge tone={STATUS[payout.status]?.tone ?? 'neutral'}>{STATUS[payout.status]?.label ?? payout.status}</Badge>,
  },
  {
    id: 'settledAt',
    header: 'Versé le',
    align: 'right',
    mobile: 'value',
    sortValue: (payout) => payout.settledAt ?? payout.paidAt ?? null,
    // Date reelle du virement (audit F504), a rapprocher du releve de l'operateur.
    cell: (payout) => {
      const settledAt = payout.settledAt ?? payout.paidAt
      return settledAt ? <span className="tnum whitespace-nowrap text-ink-2">{formatDayShort(settledAt)}</span> : <span className="text-muted">—</span>
    },
  },
  {
    id: 'settlement',
    header: 'Règlement',
    mobile: 'meta',
    className: 'hidden lg:table-cell max-w-[260px]',
    cell: (payout) => {
      if (isSettled(payout)) {
        return (
          <span className="block text-label text-ink-2">
            {payout.externalReference ? (
              <span className="tnum block truncate" title={payout.externalReference}>
                Réf. {payout.externalReference}
              </span>
            ) : (
              <span className="text-muted">Sans référence</span>
            )}
          </span>
        )
      }
      if (payout.status === 'FAILED' && payout.failureReason) {
        return (
          <span className="block text-label text-danger-ink" title={payout.failureReason}>
            {payout.failureReason}
          </span>
        )
      }
      return <span className="text-muted">—</span>
    },
  },
]

type SettleDraft = { payout: AdminPayoutResponse; reference: string; amount: string }
type FailDraft = { payout: AdminPayoutResponse; reason: string }
/** Files servies par le serveur (?status=) ; « Tous » sans filtre. PROCESSING n'existe pas encore en pratique (virement manuel). */
type PayoutFilter = Extract<PayoutStatus, 'PENDING' | 'FAILED' | 'SETTLED'> | 'ALL'

/**
 * Reversements : le decaissement mobile money se fait hors plateforme, puis se
 * consigne ici avec la reference de l'operateur (regle) ou un motif (echec). Un
 * lot en echec se relance par le meme geste une fois le virement refait.
 */
export function AdminPayouts() {
  const [filter, setFilter] = useState<PayoutFilter>('PENDING')
  const [page, setPage] = useState(0)
  const payouts = useAdminPayouts(filter, page)
  // Le total du aux conducteurs vient de la vue d'ensemble (toute la table), jamais de la page affichee.
  const overview = useAdminOverview()
  const settle = useSettlePayout()
  const fail = useFailPayout()
  const runBatch = useRunPayoutBatch()
  const [settling, setSettling] = useState<SettleDraft | null>(null)
  const [failing, setFailing] = useState<FailDraft | null>(null)
  const [runOpen, setRunOpen] = useState(false)

  const list = payouts.data?.content ?? []
  const changeFilter = (value: PayoutFilter) => {
    setFilter(value)
    setPage(0)
  }

  const settledAmount = settling && settling.amount.trim() ? Number(settling.amount) : undefined
  const settledAmountValid =
    settledAmount === undefined || (Number.isInteger(settledAmount) && settledAmount > 0 && settling !== null && settledAmount <= settling.payout.amount)

  const confirmSettle = () => {
    if (!settling || !settling.reference.trim() || !settledAmountValid) return
    const { payout } = settling
    settle.mutate(
      {
        id: payout.id,
        externalReference: settling.reference.trim(),
        settledAmountFcfa: settledAmount !== undefined && settledAmount !== payout.amount ? settledAmount : undefined,
      },
      {
        onSuccess: () => {
          toast.success('Reversement enregistré', {
            description: `${formatFcfa(settledAmount ?? payout.amount)} pour ${payout.driverName}`,
          })
          setSettling(null)
        },
        onError: (error) => toast.error(describeError(error, "Le reversement n'a pas pu être enregistré. Réessayez.")),
      },
    )
  }

  const confirmFail = () => {
    if (!failing || !failing.reason.trim()) return
    const { payout } = failing
    fail.mutate(
      { id: payout.id, reason: failing.reason.trim() },
      {
        onSuccess: () => {
          toast.success('Lot marqué en échec', {
            description: `${payout.driverName} reste à payer : relancez le lot une fois le virement refait.`,
          })
          setFailing(null)
        },
        onError: (error) => toast.error(describeError(error, "L'échec n'a pas pu être enregistré. Réessayez.")),
      },
    )
  }

  const confirmRun = () => {
    runBatch.mutate(undefined, {
      onSuccess: (result) => {
        setRunOpen(false)
        toast.success(
          result.payoutsCreated > 0
            ? `${result.payoutsCreated} lot${result.payoutsCreated > 1 ? 's' : ''} créé${result.payoutsCreated > 1 ? 's' : ''}`
            : 'Aucun lot à créer',
          {
            description:
              result.payoutsCreated > 0
                ? `${formatFcfa(result.totalAmountFcfa)} à verser aux conducteurs.`
                : 'Aucun conducteur ne dépasse le seuil de reversement cette semaine.',
          },
        )
        if (result.skipped.length > 0) {
          const names = result.skipped.map((s) => `${s.driverName} (${formatFcfa(s.amountFcfa)})`).join(', ')
          toast.warning(
            `${result.skipped.length} conducteur${result.skipped.length > 1 ? 's' : ''} sans compte mobile money vérifié`,
            {
              description: `${names}. Ils ont été prévenus ; leur solde attend le prochain lot.`,
              duration: 10_000,
            },
          )
        }
      },
      onError: (error) => toast.error(describeError(error, "Le lot n'a pas pu être constitué.")),
    })
  }

  return (
    <div>
      <AdminPageHeader
        title="Reversements"
        count={overview.data?.pendingPayouts}
        description="Lots hebdomadaires dus aux conducteurs. Le décaissement mobile money se fait hors plateforme, puis se consigne ici avec la référence de l'opérateur."
        actions={
          <Button variant="secondary" size="sm" onClick={() => setRunOpen(true)} loading={runBatch.isPending}>
            <PlayCircle className="size-4" aria-hidden />
            Constituer les lots
          </Button>
        }
      />

      <Card className="mb-4 flex items-center gap-3 p-4">
        <span className="flex size-11 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-accent-soft text-accent-ink">
          <Wallet className="size-5" aria-hidden />
        </span>
        <div>
          <p className="text-label text-muted">Reste à verser aux conducteurs</p>
          <p className="tnum font-display text-display font-extrabold leading-none tracking-[-0.03em]">
            {overview.isPending ? '…' : overview.isError ? '—' : formatFcfa(overview.data.pendingPayoutsAmountFcfa)}
          </p>
        </div>
      </Card>

      <PaymentAccountsToVerify />

      <Tabs value={filter} onValueChange={(value) => changeFilter(value as PayoutFilter)} className="mb-4">
        <TabsList>
          <TabsTrigger value="PENDING">À verser</TabsTrigger>
          <TabsTrigger value="FAILED">En échec</TabsTrigger>
          <TabsTrigger value="SETTLED">Versés</TabsTrigger>
          <TabsTrigger value="ALL">Tous</TabsTrigger>
        </TabsList>
      </Tabs>

      {payouts.isError ? (
        <ErrorState description={describeError(payouts.error)} onRetry={() => payouts.refetch()} />
      ) : (
        <DataTable
          caption="Lots de reversement"
          columns={COLUMNS}
          rows={list}
          rowKey={(payout) => payout.id}
          loading={payouts.isPending}
          // Le serveur sert les lots du plus recent au plus ancien ; le tri client ne porte que sur la page affichee.
          initialSort={{ id: 'period', direction: 'desc' }}
          rowAccent={(payout) => ACCENT[payout.status]}
          empty={
            <EmptyState
              icon={Banknote}
              title="Aucun reversement"
              description={
                filter === 'PENDING'
                  ? "Aucun lot à verser pour l'instant. Constituez les lots de la semaine pour les conducteurs au-dessus du seuil."
                  : 'Aucun lot dans cette file.'
              }
            />
          }
          rowActions={(payout) =>
            isDue(payout) ? (
              <span className="flex flex-wrap justify-end gap-1">
                {payout.status !== 'FAILED' ? (
                  <Button
                    size="sm"
                    variant="ghost"
                    className="text-danger-ink"
                    onClick={() => setFailing({ payout, reason: '' })}
                  >
                    <CircleX className="size-4" aria-hidden />
                    Marquer en échec
                  </Button>
                ) : null}
                <Button size="sm" onClick={() => setSettling({ payout, reference: '', amount: '' })}>
                  {payout.status === 'FAILED' ? (
                    <>
                      <RotateCcw className="size-4" aria-hidden />
                      Relancer
                    </>
                  ) : (
                    'Marquer réglé'
                  )}
                </Button>
              </span>
            ) : null
          }
        />
      )}
      {payouts.data ? (
        <AdminPagination
          page={payouts.data.number}
          totalPages={payouts.data.totalPages}
          onPageChange={setPage}
          busy={payouts.isFetching}
          label="Pages de la liste des reversements"
        />
      ) : null}

      <ConfirmDialog
        open={settling !== null}
        onOpenChange={(open) => !open && setSettling(null)}
        title={settling?.payout.status === 'FAILED' ? 'Relancer et régler ce lot ?' : 'Confirmer le versement ?'}
        description={
          settling
            ? `${formatFcfa(settling.payout.amount)} pour ${settling.payout.driverName}, vers ${accountLabel(settling.payout)}. Ne confirmez qu'une fois le transfert mobile money réellement effectué : cette action est définitive et journalisée.`
            : undefined
        }
        confirmLabel="Oui, réglé"
        confirmDisabled={!settling || !settling.reference.trim() || !settledAmountValid}
        loading={settle.isPending}
        onConfirm={confirmSettle}
      >
        {settling ? (
          <div className="space-y-3">
            <Input
              label="Référence du virement"
              hint="Obligatoire. Identifiant de l'opération chez l'opérateur (reçu MoMo, Moov, Celtiis)."
              placeholder="Ex. MP240905.1432.A12345"
              value={settling.reference}
              onChange={(event) => setSettling((s) => (s ? { ...s, reference: event.target.value } : s))}
              spellCheck={false}
              autoComplete="off"
            />
            <Input
              label="Montant réellement versé (FCFA)"
              hint={`Laissez vide si ${formatFcfa(settling.payout.amount)} ont été versés. À renseigner seulement si l'opérateur a retenu des frais.`}
              type="number"
              inputMode="numeric"
              min={1}
              max={settling.payout.amount}
              step={5}
              value={settling.amount}
              onChange={(event) => setSettling((s) => (s ? { ...s, amount: event.target.value } : s))}
              error={settledAmountValid ? undefined : `Montant entier entre 1 et ${formatFcfa(settling.payout.amount)}.`}
            />
          </div>
        ) : null}
      </ConfirmDialog>

      <ConfirmDialog
        open={failing !== null}
        onOpenChange={(open) => !open && setFailing(null)}
        title="Marquer ce lot en échec ?"
        description={
          failing
            ? `${formatFcfa(failing.payout.amount)} pour ${failing.payout.driverName}. Le lot reste dû : il se relancera une fois le virement refait. Le motif est journalisé.`
            : undefined
        }
        tone="danger"
        confirmLabel="Marquer en échec"
        confirmDisabled={!failing?.reason.trim()}
        loading={fail.isPending}
        onConfirm={confirmFail}
      >
        <Textarea
          label="Motif de l'échec"
          hint="Obligatoire. Numéro invalide, plafond de compte atteint, opérateur en panne…"
          rows={3}
          maxLength={500}
          value={failing?.reason ?? ''}
          onChange={(event) => setFailing((f) => (f ? { ...f, reason: event.target.value } : f))}
        />
      </ConfirmDialog>

      <ConfirmDialog
        open={runOpen}
        onOpenChange={setRunOpen}
        title="Constituer les lots de la semaine ?"
        description="Chaque conducteur dont le solde net dépasse le seuil reçoit un lot « à verser », calculé sur les réservations payées en mobile money et non encore reversées. Aucun argent ne part : le virement reste manuel."
        confirmLabel="Constituer"
        loading={runBatch.isPending}
        onConfirm={confirmRun}
      />
    </div>
  )
}
