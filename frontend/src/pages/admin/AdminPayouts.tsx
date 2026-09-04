import { motion } from 'motion/react'
import { Banknote, Wallet } from 'lucide-react'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/misc'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { providerLabel } from '@/lib/payments'
import { useAdminPayouts, useMarkPayoutPaid } from '@/hooks/useAdmin'
import { formatDayShort, formatFcfa, formatPhone } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'
import type { PayoutStatus } from '@/api/extended'

const STATUS: Record<PayoutStatus, { label: string; tone: 'warning' | 'indigo' | 'success' | 'danger' }> = {
  PENDING: { label: 'À verser', tone: 'warning' },
  PROCESSING: { label: 'En cours', tone: 'indigo' },
  PAID: { label: 'Versé', tone: 'success' },
  FAILED: { label: 'Échec', tone: 'danger' },
}

export function AdminPayouts() {
  const payouts = useAdminPayouts()
  const markPaid = useMarkPayoutPaid()

  const list = payouts.data?.data ?? []
  const pendingTotal = list
    .filter((p) => p.status === 'PENDING' || p.status === 'FAILED')
    .reduce((sum, p) => sum + p.amount, 0)

  return (
    <div>

      <Card className="mb-4 flex items-center gap-3 p-4">
        <span className="flex size-11 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-[var(--ocre-soft)] text-[var(--ocre-ink)]">
          <Wallet className="size-5" aria-hidden />
        </span>
        <div>
          <p className="text-[13px] text-muted">Reste à verser aux conducteurs</p>
          <p className="tnum font-display text-[24px] font-extrabold leading-none tracking-[-0.03em]">
            {formatFcfa(pendingTotal)}
          </p>
        </div>
      </Card>

      {payouts.isPending ? (
        <div className="space-y-2">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-24 rounded-[var(--radius-card)]" />
          ))}
        </div>
      ) : payouts.isError ? (
        <ErrorState onRetry={() => payouts.refetch()} />
      ) : list.length === 0 ? (
        <EmptyState icon={Banknote} title="Aucun reversement" description="Aucune période à régler pour l'instant." />
      ) : (
        <motion.ul variants={listContainer} initial="hidden" animate="show" className="space-y-2">
          {list.map((payout) => (
            <motion.li key={payout.id} variants={listItem} layout>
              <Card>
                <div className="flex flex-wrap items-start gap-3 p-4">
                  <div className="min-w-0 flex-1">
                    <p className="font-display text-[16px] font-bold">{payout.driverName}</p>
                    <p className="tnum text-[13px] text-muted">
                      {providerLabel(payout.provider)} ·{' '}
                      {formatPhone(payout.phone)}
                    </p>
                    <p className="tnum mt-1 text-[13px] text-ink-2">
                      {payout.tripCount} trajet{payout.tripCount > 1 ? 's' : ''} · {formatDayShort(payout.periodStart)}{' '}
                      → {formatDayShort(payout.periodEnd)}
                    </p>
                  </div>
                  <div className="text-right">
                    <p className="tnum font-display text-[20px] font-extrabold leading-none tracking-[-0.03em]">
                      {formatFcfa(payout.amount)}
                    </p>
                    <Badge tone={STATUS[payout.status].tone} className="mt-1.5">
                      {STATUS[payout.status].label}
                    </Badge>
                  </div>
                </div>

                {payout.status === 'PENDING' || payout.status === 'FAILED' ? (
                  <div className="flex justify-end border-t border-rule px-3 py-2.5">
                    <Button
                      size="sm"
                      onClick={() =>
                        markPaid.mutate(payout.id, {
                          onSuccess: () =>
                            toast.success('Reversement lancé', {
                              description: `${formatFcfa(payout.amount)} vers ${formatPhone(payout.phone)}`,
                            }),
                          onError: () => toast.error('Le reversement a échoué.'),
                        })
                      }
                    >
                      {payout.status === 'FAILED' ? 'Relancer le versement' : 'Marquer comme versé'}
                    </Button>
                  </div>
                ) : null}
              </Card>
            </motion.li>
          ))}
        </motion.ul>
      )}
    </div>
  )
}
