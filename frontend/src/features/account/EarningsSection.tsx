import { motion } from 'motion/react'
import { Banknote, Wallet } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import { Progress, Skeleton } from '@/components/ui/misc'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { SectionTitle } from '@/components/layout/PageContainer'
import { useDriverBalance, useMyPayouts } from '@/hooks/useAccount'
import { formatDayShort, formatFcfa, formatPhone } from '@/lib/format'
import { listContainer, listItem } from '@/lib/motion'
import type { PayoutStatus } from '@/api/extended'

const STATUS: Record<PayoutStatus, { label: string; tone: 'warning' | 'indigo' | 'success' | 'danger' }> = {
  PENDING: { label: 'En préparation', tone: 'warning' },
  PROCESSING: { label: 'En cours de virement', tone: 'indigo' },
  PAID: { label: 'Versé', tone: 'success' },
  SETTLED: { label: 'Versé', tone: 'success' },
  FAILED: { label: 'Échec, relance en cours', tone: 'danger' },
}

/**
 * Revenus du conducteur : solde net en attente (reservations payees en mobile
 * money, commission deduite) et lots de reversement. Les montants viennent du
 * serveur (regle metier n.4) ; rien n'est recalcule ici.
 */
export function EarningsSection() {
  const balance = useDriverBalance()
  const payouts = useMyPayouts()
  const list = payouts.data ?? []

  return (
    <div>
      <SectionTitle>Solde à reverser</SectionTitle>
      {balance.isPending ? (
        <Skeleton className="h-28 rounded-[var(--radius-card)]" />
      ) : balance.isError ? (
        <ErrorState onRetry={() => balance.refetch()} />
      ) : (
        <Card className="p-5">
          <div className="flex items-start gap-3">
            <span className="flex size-11 shrink-0 items-center justify-center rounded-[var(--radius-control)] bg-primary-soft text-primary-ink">
              <Wallet className="size-5" aria-hidden />
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-label text-muted">Encaissé en ligne, commission déduite</p>
              <p className="tnum font-display text-display font-extrabold leading-none tracking-[-0.03em]">
                {formatFcfa(balance.data.pendingBalanceFcfa)}
              </p>
            </div>
          </div>
          <Progress
            className="mt-4"
            value={Math.min(
              100,
              Math.round((balance.data.pendingBalanceFcfa / Math.max(1, balance.data.minimumPayoutThresholdFcfa)) * 100),
            )}
            aria-label="Progression vers le seuil de reversement"
          />
          <p className="mt-2 text-[13px] leading-relaxed text-ink-2">
            {balance.data.pendingBalanceFcfa >= balance.data.minimumPayoutThresholdFcfa
              ? 'Seuil atteint : ce solde sera inclus dans le prochain lot hebdomadaire.'
              : `Les reversements partent chaque semaine dès ${formatFcfa(balance.data.minimumPayoutThresholdFcfa)} de solde. Le solde en espèces réglé à bord ne transite pas par Ekuiseo.`}
          </p>
        </Card>
      )}

      <SectionTitle className="mt-5">Reversements</SectionTitle>
      {payouts.isPending ? (
        <div className="space-y-2">
          <Skeleton className="h-[72px] rounded-[var(--radius-card)]" />
          <Skeleton className="h-[72px] rounded-[var(--radius-card)]" />
        </div>
      ) : payouts.isError ? (
        <ErrorState onRetry={() => payouts.refetch()} />
      ) : list.length === 0 ? (
        <Card>
          <EmptyState
            icon={Banknote}
            title="Aucun reversement pour l'instant"
            description="Votre premier lot apparaîtra ici dès que votre solde dépassera le seuil."
            className="py-8"
          />
        </Card>
      ) : (
        <motion.ul variants={listContainer} initial="hidden" animate="show" className="space-y-2">
          {list.map((payout) => (
            <motion.li key={payout.id} variants={listItem}>
              <Card className="flex items-center gap-3 p-4">
                <div className="min-w-0 flex-1">
                  <p className="tnum font-display text-[16px] font-bold">{formatFcfa(payout.amount)}</p>
                  <p className="tnum text-[13px] text-muted">
                    {formatDayShort(payout.periodStart)} → {formatDayShort(payout.periodEnd)}
                    {payout.destinationMsisdn ? ` · ${formatPhone(payout.destinationMsisdn)}` : ''}
                  </p>
                </div>
                <Badge tone={STATUS[payout.status]?.tone ?? 'neutral'}>{STATUS[payout.status]?.label ?? payout.status}</Badge>
              </Card>
            </motion.li>
          ))}
        </motion.ul>
      )}
    </div>
  )
}
