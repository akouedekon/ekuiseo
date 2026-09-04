import { Banknote, Wallet } from 'lucide-react'
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
import { useAdminPayouts, useMarkPayoutPaid } from '@/hooks/useAdmin'
import { formatDayShort, formatFcfa, formatPhone } from '@/lib/format'
import type { AdminPayoutResponse, PayoutStatus } from '@/api/extended'

const STATUS: Record<PayoutStatus, { label: string; tone: 'warning' | 'indigo' | 'success' | 'danger'; order: number }> = {
  PENDING: { label: 'À verser', tone: 'warning', order: 0 },
  FAILED: { label: 'Échec', tone: 'danger', order: 1 },
  PROCESSING: { label: 'En cours', tone: 'indigo', order: 2 },
  PAID: { label: 'Versé', tone: 'success', order: 3 },
}

const ACCENT: Partial<Record<PayoutStatus, string>> = {
  PENDING: 'var(--ocre)',
  FAILED: 'var(--vermillon)',
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
        <span className="block">{providerLabel(payout.provider)}</span>
        <span className="block whitespace-nowrap text-muted">{formatPhone(payout.phone)}</span>
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
    cell: (payout) => <span className="font-display font-bold text-ink">{formatFcfa(payout.amount)}</span>,
  },
  {
    id: 'status',
    header: 'Statut',
    mobile: 'badge',
    sortValue: (payout) => STATUS[payout.status].order,
    cell: (payout) => <Badge tone={STATUS[payout.status].tone}>{STATUS[payout.status].label}</Badge>,
  },
]

export function AdminPayouts() {
  const payouts = useAdminPayouts()
  const markPaid = useMarkPayoutPaid()
  const [target, setTarget] = useState<AdminPayoutResponse | null>(null)

  const list = payouts.data?.data ?? []
  const due = list.filter((p) => p.status === 'PENDING' || p.status === 'FAILED')
  const pendingTotal = due.reduce((sum, p) => sum + p.amount, 0)

  const confirmPaid = () => {
    if (!target) return
    const payout = target
    markPaid.mutate(payout.id, {
      onSuccess: () =>
        toast.success('Reversement enregistré', {
          description: `${formatFcfa(payout.amount)} vers ${formatPhone(payout.phone)}`,
        }),
      onError: () => toast.error("Le reversement n'a pas pu être enregistré. Réessayez."),
      onSettled: () => setTarget(null),
    })
  }

  return (
    <div>
      <AdminPageHeader
        title="Reversements"
        count={payouts.isSuccess ? due.length : undefined}
        description="Lots hebdomadaires dus aux conducteurs. Le décaissement mobile money se fait hors plateforme, puis se marque ici comme versé."
      />

      <Card className="mb-4 flex items-center gap-3 p-4">
        <span className="flex size-11 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-[var(--ocre-soft)] text-[var(--ocre-ink)]">
          <Wallet className="size-5" aria-hidden />
        </span>
        <div>
          <p className="text-label text-muted">Reste à verser aux conducteurs</p>
          <p className="tnum font-display text-display font-extrabold leading-none tracking-[-0.03em]">
            {payouts.isPending ? '…' : formatFcfa(pendingTotal)}
          </p>
        </div>
      </Card>

      {payouts.isError ? (
        <ErrorState onRetry={() => payouts.refetch()} />
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
            <EmptyState icon={Banknote} title="Aucun reversement" description="Aucune période à régler pour l'instant." />
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
            ? `${formatFcfa(target.amount)} pour ${target.driverName}, vers ${providerLabel(target.provider)} ${formatPhone(target.phone)}. Ne confirmez qu'une fois le transfert mobile money réellement effectué : cette action est définitive.`
            : undefined
        }
        confirmLabel="Oui, versé"
        loading={markPaid.isPending}
        onConfirm={confirmPaid}
      />
    </div>
  )
}
