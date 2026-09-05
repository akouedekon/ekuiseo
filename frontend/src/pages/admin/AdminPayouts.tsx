import { Banknote, PlayCircle, Wallet } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { AdminPageHeader } from '@/components/layout/AdminPageHeader'
import { DataTable, type DataTableColumn } from '@/components/tables/DataTable'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { providerLabel } from '@/lib/payments'
import { PaymentAccountsToVerify } from '@/features/admin/PaymentAccountsToVerify'
import { useAdminPayouts, useMarkPayoutPaid, useRunPayoutBatch } from '@/hooks/useAdmin'
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
  PENDING: 'var(--ocre)',
  FAILED: 'var(--vermillon)',
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
    cell: (payout) => <span className="font-semibold text-ink">{payout.driverName}</span>,
  },
  {
    id: 'account',
    header: 'Compte',
    mobile: 'meta',
    className: 'hidden xl:table-cell',
    cell: (payout) => (
      <span className="tnum block text-label text-ink-2">
        {payout.provider ? <span className="block">{providerLabel(payout.provider)}</span> : null}
        <span className={payout.phone ? 'block whitespace-nowrap text-muted' : 'block text-[var(--vermillon)]'}>
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
          <span className="block text-[12px] font-normal text-[var(--vermillon)]">
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
]

export function AdminPayouts() {
  const payouts = useAdminPayouts()
  const markPaid = useMarkPayoutPaid()
  const runBatch = useRunPayoutBatch()
  const [target, setTarget] = useState<AdminPayoutResponse | null>(null)
  const [runOpen, setRunOpen] = useState(false)

  const list = payouts.data ?? []
  const due = list.filter((p) => p.status === 'PENDING' || p.status === 'FAILED')
  const pendingTotal = due.reduce((sum, p) => sum + p.amount, 0)

  const confirmPaid = () => {
    if (!target) return
    const payout = target
    markPaid.mutate(payout.id, {
      onSuccess: () => {
        toast.success('Reversement enregistré', {
          description: `${formatFcfa(payout.amount)} pour ${payout.driverName}`,
        })
        setTarget(null)
      },
      onError: (error) => toast.error(describeError(error, "Le reversement n'a pas pu être enregistré. Réessayez.")),
    })
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
        count={payouts.isSuccess ? due.length : undefined}
        description="Lots hebdomadaires dus aux conducteurs. Le décaissement mobile money se fait hors plateforme, puis se marque ici comme versé."
        actions={
          <Button variant="secondary" size="sm" onClick={() => setRunOpen(true)} loading={runBatch.isPending}>
            <PlayCircle className="size-4" aria-hidden />
            Constituer les lots
          </Button>
        }
      />

      <Card className="mb-4 flex items-center gap-3 p-4">
        <span className="flex size-11 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-[var(--ocre-soft)] text-[var(--ocre-ink)]">
          <Wallet className="size-5" aria-hidden />
        </span>
        <div>
          <p className="text-label text-muted">Reste à verser aux conducteurs</p>
          <p className="tnum font-display text-display font-extrabold leading-none tracking-[-0.03em]">
            {payouts.isPending ? '…' : payouts.isError ? '—' : formatFcfa(pendingTotal)}
          </p>
        </div>
      </Card>

      <PaymentAccountsToVerify />

      {payouts.isError ? (
        <ErrorState description={describeError(payouts.error)} onRetry={() => payouts.refetch()} />
      ) : (
        <DataTable
          caption="Lots de reversement"
          columns={COLUMNS}
          rows={list}
          rowKey={(payout) => payout.id}
          loading={payouts.isPending}
          initialSort={{ id: 'status', direction: 'asc' }}
          rowAccent={(payout) => ACCENT[payout.status]}
          empty={
            <EmptyState
              icon={Banknote}
              title="Aucun reversement"
              description="Aucun lot pour l'instant. Constituez les lots de la semaine pour les conducteurs au-dessus du seuil."
            />
          }
          rowActions={(payout) =>
            payout.status === 'PENDING' || payout.status === 'FAILED' ? (
              <Button size="sm" onClick={() => setTarget(payout)}>
                {payout.status === 'FAILED' ? 'Relancer' : 'Marquer versé'}
              </Button>
            ) : null
          }
        />
      )}

      <ConfirmDialog
        open={target !== null}
        onOpenChange={(open) => !open && setTarget(null)}
        title="Confirmer le versement ?"
        description={
          target
            ? `${formatFcfa(target.amount)} pour ${target.driverName}, vers ${accountLabel(target)}. Ne confirmez qu'une fois le transfert mobile money réellement effectué : cette action est définitive.`
            : undefined
        }
        confirmLabel="Oui, versé"
        loading={markPaid.isPending}
        onConfirm={confirmPaid}
      />

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
