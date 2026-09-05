import { Banknote, CheckCircle2, RefreshCw, RotateCcw } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/feedback/ConfirmDialog'
import { AdminPageHeader } from '@/components/layout/AdminPageHeader'
import { DataTable, type DataTableColumn } from '@/components/tables/DataTable'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/input'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useAdminPayments, useMarkPaymentRefunded, useRetryRefund } from '@/hooks/useAdmin'
import { describeError } from '@/lib/errors'
import { formatDayShort, formatFcfa, formatPhone } from '@/lib/format'
import type { AdminPaymentResponse, AdminPaymentsFilter, PaymentRecordStatus } from '@/api/extended'

const STATUS: Record<PaymentRecordStatus, { label: string; tone: 'warning' | 'danger' | 'success' | 'neutral' | 'indigo' }> = {
  INITIATED: { label: 'Initié', tone: 'neutral' },
  SUCCEEDED: { label: 'Encaissé', tone: 'success' },
  FAILED: { label: 'Échec', tone: 'neutral' },
  REFUND_PENDING: { label: 'Remboursement en cours', tone: 'warning' },
  REFUND_MANUAL: { label: 'À traiter à la main', tone: 'danger' },
  REFUNDED: { label: 'Remboursé', tone: 'success' },
}

const REASON: Record<string, string> = {
  ANNULATION_PASSAGER: 'Annulation par le passager',
  ANNULATION_CONDUCTEUR: 'Annulation par le conducteur',
  PAYMENT_ORPHAN: 'Paiement reçu après expiration ou annulation',
  AMOUNT_INSUFFICIENT: 'Montant encaissé insuffisant',
}

const COLUMNS: DataTableColumn<AdminPaymentResponse>[] = [
  {
    id: 'passenger',
    header: 'Passager',
    mobile: 'title',
    sortValue: (p) => p.passengerName ?? '',
    cell: (p) => (
      <span className="block min-w-0">
        <span className="block truncate font-semibold text-ink">{p.passengerName ?? 'Abonnement conducteur'}</span>
        <span className="tnum block truncate text-label text-muted">
          {p.passengerPhone ? formatPhone(p.passengerPhone) : p.subscriptionId ? 'Abonnement' : '—'}
        </span>
      </span>
    ),
  },
  {
    id: 'reason',
    header: 'Motif',
    mobile: 'meta',
    cell: (p) => (
      <span className="block text-label text-ink-2">
        {p.refundReason ? (REASON[p.refundReason] ?? p.refundReason) : '—'}
        {p.refundLastError ? <span className="block text-[12px] text-[var(--vermillon)]">{p.refundLastError}</span> : null}
      </span>
    ),
  },
  {
    id: 'amount',
    header: 'À rembourser',
    align: 'right',
    mobile: 'value',
    sortValue: (p) => p.refundAmount ?? p.amount,
    cell: (p) => (
      <span className="font-display font-bold text-ink">
        {formatFcfa(p.refundAmount ?? p.amount)}
        {p.refundAmount !== null && p.refundAmount !== p.amount ? (
          <span className="block text-[12px] font-normal text-muted">sur {formatFcfa(p.amount)} encaissés</span>
        ) : null}
      </span>
    ),
  },
  {
    id: 'requested',
    header: 'Demandé le',
    align: 'right',
    mobile: 'value',
    className: 'hidden xl:table-cell',
    sortValue: (p) => p.refundRequestedAt ?? p.createdAt,
    cell: (p) => (
      <span className="tnum text-ink-2">
        {formatDayShort(p.refundRequestedAt ?? p.createdAt)}
        {p.refundAttempts > 0 ? <span className="block text-[12px] text-muted">{p.refundAttempts} tentative{p.refundAttempts > 1 ? 's' : ''}</span> : null}
      </span>
    ),
  },
  {
    id: 'status',
    header: 'Statut',
    mobile: 'badge',
    sortValue: (p) => p.status,
    cell: (p) => <Badge tone={STATUS[p.status]?.tone ?? 'neutral'}>{STATUS[p.status]?.label ?? p.status}</Badge>,
  },
]

const FILTERS: { value: AdminPaymentsFilter; label: string }[] = [
  { value: 'TODO', label: 'À traiter' },
  { value: 'REFUNDED', label: 'Remboursés' },
  { value: 'ALL', label: 'Tous' },
]

/**
 * File des remboursements : ce que l'automate n'a pas pu finir (Kkiapay injoignable,
 * montant partiel, paiement sans identifiant Kkiapay) et ce qu'il a fini. Un
 * remboursement « à traiter à la main » se fait depuis le tableau de bord Kkiapay,
 * puis se marque ici comme remboursé (journalisé, passager prévenu).
 */
export function AdminPayments() {
  const [filter, setFilter] = useState<AdminPaymentsFilter>('TODO')
  const [target, setTarget] = useState<AdminPaymentResponse | null>(null)
  const [note, setNote] = useState('')
  const payments = useAdminPayments(filter)
  const retry = useRetryRefund()
  const markRefunded = useMarkPaymentRefunded()

  const list = payments.data ?? []
  const todo = list.filter((p) => p.status === 'REFUND_PENDING' || p.status === 'REFUND_MANUAL')

  const doRetry = (payment: AdminPaymentResponse) => {
    retry.mutate(payment.id, {
      onSuccess: (updated) => {
        if (updated.status === 'REFUNDED') toast.success('Remboursement confirmé par Kkiapay')
        else toast.warning('Kkiapay n’a pas confirmé', { description: updated.refundLastError ?? 'Nouvelle tentative programmée.' })
      },
      onError: (error) => toast.error(describeError(error, "La relance n'a pas abouti.")),
    })
  }

  const confirmMark = () => {
    if (!target) return
    const payment = target
    markRefunded.mutate(
      { id: payment.id, note: note.trim() || undefined },
      {
        onSuccess: () => {
          toast.success('Remboursement enregistré', {
            description: `${formatFcfa(payment.refundAmount ?? payment.amount)} pour ${payment.passengerName ?? 'ce paiement'}.`,
          })
          setTarget(null)
          setNote('')
        },
        onError: (error) => toast.error(describeError(error, "L'enregistrement n'a pas abouti.")),
      },
    )
  }

  return (
    <div>
      <AdminPageHeader
        title="Paiements"
        count={payments.isSuccess && filter === 'TODO' ? todo.length : undefined}
        description="Remboursements à suivre. L'automate rejoue les échecs toutes les 5 minutes ; ce qui reste ici demande une action : relancer, ou rembourser depuis le tableau de bord Kkiapay puis marquer remboursé."
      />

      <Tabs value={filter} onValueChange={(value) => setFilter(value as AdminPaymentsFilter)} className="mb-4">
        <TabsList>
          {FILTERS.map((f) => (
            <TabsTrigger key={f.value} value={f.value}>
              {f.label}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      {payments.isError ? (
        <ErrorState description={describeError(payments.error)} onRetry={() => payments.refetch()} />
      ) : (
        <DataTable
          caption="Paiements à suivre"
          columns={COLUMNS}
          rows={list}
          rowKey={(p) => p.id}
          loading={payments.isPending}
          initialSort={{ id: 'requested', direction: 'desc' }}
          rowAccent={(p) => (p.status === 'REFUND_MANUAL' ? 'var(--vermillon)' : p.status === 'REFUND_PENDING' ? 'var(--ocre)' : undefined)}
          empty={
            <EmptyState
              icon={Banknote}
              title={filter === 'TODO' ? 'Rien à traiter' : 'Aucun paiement'}
              description={filter === 'TODO' ? 'Tous les remboursements demandés ont été exécutés.' : 'Aucun paiement dans cette vue.'}
            />
          }
          rowActions={(p) =>
            p.status === 'REFUND_PENDING' || p.status === 'REFUND_MANUAL' ? (
              <span className="flex flex-wrap justify-end gap-1">
                <Button size="sm" variant="ghost" onClick={() => doRetry(p)} loading={retry.isPending && retry.variables === p.id}>
                  <RefreshCw className="size-4" aria-hidden />
                  Relancer
                </Button>
                <Button size="sm" onClick={() => setTarget(p)}>
                  <CheckCircle2 className="size-4" aria-hidden />
                  Marquer remboursé
                </Button>
              </span>
            ) : null
          }
        />
      )}

      <ConfirmDialog
        open={target !== null}
        onOpenChange={(open) => !open && setTarget(null)}
        title="Confirmer le remboursement manuel ?"
        description={
          target
            ? `${formatFcfa(target.refundAmount ?? target.amount)} pour ${target.passengerName ?? 'ce paiement'}. Ne confirmez qu'une fois le remboursement réellement effectué depuis Kkiapay : le passager sera prévenu et l'opération journalisée.`
            : undefined
        }
        confirmLabel="Oui, remboursé"
        loading={markRefunded.isPending}
        onConfirm={confirmMark}
      >
        <Textarea
          label="Note"
          hint="Référence Kkiapay, date, canal. Conservée dans le journal d'audit."
          rows={2}
          maxLength={300}
          value={note}
          onChange={(event) => setNote(event.target.value)}
        />
      </ConfirmDialog>

      <p className="mt-4 flex items-start gap-2 text-[12px] leading-relaxed text-muted">
        <RotateCcw className="mt-0.5 size-4 shrink-0" aria-hidden />
        Un remboursement partiel (annulation entre 24 h et l'heure du départ : 50 % retenus) n'est pas automatisable
        avec l'API Kkiapay : il se fait depuis le tableau de bord Kkiapay, puis se marque ici.
      </p>
    </div>
  )
}
